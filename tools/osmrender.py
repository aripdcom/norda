#!/usr/bin/env python3
"""Real OSM cartography for Norda map packs — standard library only.

This is the "build the pack" step's renderer (docs/MVP.md 7.2). It turns an
Overpass API response into 256×256 PNG tiles in Norda's minimalist outdoor
style. Everything downstream — the MBTiles layout, the release and the
index.json chain — is untouched.

Pipeline
  fetch_region()   Overpass QL, split into ≤0.2° cells with retries; the
                   coastline is fetched for the tile-aligned extent of the
                   smallest zoom so coarse tiles get their sea right.
  parse_overpass() elements → Feature(layer, cls, geometry) — coastline,
                   water/green areas (ways and multipolygon outers), rivers,
                   roads by class, rails.
  sea_rings()      coastline ways → sea polygons: OSM draws coastlines with
                   land on the LEFT and water on the RIGHT, so open chains are
                   closed clockwise along the extent boundary; closed rings
                   become holes (islands) or fills (lagoons) under even-odd.
  Scene.render_tile()  land → sea → green → water → waterways → road casings →
                   road fills → rails, drawn at 2× and box-downsampled for
                   anti-aliasing; no labels (no fonts without dependencies —
                   a deliberate v1 limit).

Style values are starting points to be calibrated in the field, like the GPS
filter thresholds. Data © OpenStreetMap contributors (ODbL).
"""

import json
import math
import struct
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zlib

import fontdata

TILE = 256
EPS = 1e-9

# --------------------------------------------------------------------------
# Tile math (Web Mercator, same formulas as core/map/WebMercator.kt)

def x_tile(lon, z):
    return (lon + 180.0) / 360.0 * (1 << z)


def y_tile(lat, z):
    r = math.radians(lat)
    return (1.0 - math.log(math.tan(r) + 1.0 / math.cos(r)) / math.pi) / 2.0 * (1 << z)


def tile_lon(x, z):
    return x / (1 << z) * 360.0 - 180.0


def tile_lat(y, z):
    n = math.pi - 2.0 * math.pi * y / (1 << z)
    return math.degrees(math.atan(math.sinh(n)))


def tile_bounds(z, x, y):
    """(left, bottom, right, top) of tile x,y at zoom z, in lon/lat."""
    return (tile_lon(x, z), tile_lat(y + 1, z), tile_lon(x + 1, z), tile_lat(y, z))


def tile_range(bbox, z):
    left, bottom, right, top = bbox
    return (int(x_tile(left, z)), int(x_tile(right, z)),
            int(y_tile(top, z)), int(y_tile(bottom, z)))


def coast_extent(bbox, minzoom):
    """Tile-aligned extent at the smallest zoom: the coastline is fetched and
    the sea is solved for this rectangle so even the coarsest tiles show the
    right land/sea split, not just the part inside the pack bbox."""
    x0, x1, y0, y1 = tile_range(bbox, minzoom)
    l, b0, _, _ = tile_bounds(minzoom, x0, y1)
    _, _, r, t = tile_bounds(minzoom, x1, y0)
    return (l, b0, r, t)


# --------------------------------------------------------------------------
# Style

STYLE = {
    "land": (243, 240, 233),
    "sea": (170, 204, 222),
    "water": (170, 204, 222),
    "waterway": (150, 190, 214),
    "green": (206, 226, 196),        # forest / wood
    "green_light": (222, 234, 208),  # park, grass, meadow, cemetery …
    "road_casing": (168, 166, 158),
    "road_motorway": (247, 196, 118),
    "road_trunk": (249, 212, 150),
    "road_primary": (255, 236, 187),
    "road_secondary": (255, 255, 255),
    "road_minor": (255, 255, 255),
    "road_track": (152, 122, 92),
    "rail": (120, 120, 120),
    "label": (58, 56, 52),
    "label_water": (70, 110, 140),
    "label_peak": (92, 72, 50),
    "halo": (252, 252, 250),
}

# Labels (v2 of the cartography): place names, peaks and water names from
# OSM nodes and named water areas. Zoom floor per class; drawn last, with a
# halo, greedy by priority so nothing overlaps.
LABEL_MIN_ZOOM = {
    "sea": 8, "city": 8, "town": 10, "strait": 10, "bay": 10, "island": 11,
    "peak": 11, "village": 11, "water": 11, "cape": 12, "suburb": 12,
    "quarter": 12, "neighbourhood": 13, "hamlet": 13, "locality": 13,
}
LABEL_PRIORITY = {
    "sea": 0, "city": 1, "town": 2, "strait": 3, "bay": 4, "island": 5,
    "village": 6, "peak": 7, "water": 8, "suburb": 9, "quarter": 10,
    "cape": 11, "neighbourhood": 12, "hamlet": 13, "locality": 14,
}
PLACE_CLASSES = {"city", "town", "village", "suburb", "quarter", "neighbourhood",
                 "hamlet", "locality", "island", "sea"}
NATURAL_LABELS = {"peak", "bay", "strait", "cape"}


def label_style(cls, z):
    """(font variant, colour, symbol) or None when the class is not labelled at z.
    Variants are 2× sizes: r20/r24/r28 → 10/12/14 px text, b = bold."""
    floor = LABEL_MIN_ZOOM.get(cls)
    if floor is None or z < floor:
        return None
    if cls == "sea":
        return ("b28", STYLE["label_water"], None)
    if cls == "city":
        return ("b28" if z <= 11 else "b24", STYLE["label"], "dot")
    if cls == "town":
        return ("b24", STYLE["label"], "dot")
    if cls in ("strait", "bay"):
        return ("r24", STYLE["label_water"], None)
    if cls == "water":
        return ("r20", STYLE["label_water"], None)
    if cls == "peak":
        return ("r20", STYLE["label_peak"], "peak")
    if cls in ("island", "village"):
        return ("r24", STYLE["label"], "dot" if cls == "village" else None)
    if cls in ("suburb", "quarter", "cape"):
        return ("r24" if z >= 13 else "r20", STYLE["label"], None)
    return ("r20", STYLE["label"], None)

ROAD_CLASS = {
    "motorway": "motorway", "motorway_link": "motorway",
    "trunk": "trunk", "trunk_link": "trunk",
    "primary": "primary", "primary_link": "primary",
    "secondary": "secondary", "secondary_link": "secondary",
    "tertiary": "secondary", "tertiary_link": "secondary",
    "unclassified": "minor", "residential": "minor", "living_street": "minor",
    "track": "track", "path": "track",
}
# draw order: minor first so major roads sit on top
ROAD_RANK = {"track": 0, "minor": 1, "secondary": 2, "primary": 3, "trunk": 4, "motorway": 5}

GREEN_TAGS = {
    ("natural", "wood"): "green", ("landuse", "forest"): "green",
    ("leisure", "park"): "green_light", ("leisure", "garden"): "green_light",
    ("leisure", "nature_reserve"): "green_light", ("leisure", "golf_course"): "green_light",
    ("leisure", "pitch"): "green_light", ("landuse", "grass"): "green_light",
    ("landuse", "meadow"): "green_light", ("landuse", "recreation_ground"): "green_light",
    ("landuse", "cemetery"): "green_light", ("landuse", "orchard"): "green_light",
    ("landuse", "vineyard"): "green_light",
}
WATERWAYS = {"river", "canal", "stream"}


def road_style(highway, z):
    """(fill color, width px, casing width px) in 256-px tile space, or None
    when the class is not drawn at this zoom."""
    cls = ROAD_CLASS.get(highway, highway)
    if cls in ("motorway", "trunk"):
        if z < 8:
            return None
        w = {8: 1.5, 9: 1.8, 10: 2.2, 11: 2.6, 12: 3.2}.get(z, 4.0)
        return (STYLE["road_" + cls], w, w + 1.6 if z >= 10 else 0.0)
    if cls == "primary":
        if z < 9:
            return None
        w = {9: 1.2, 10: 1.6, 11: 2.0, 12: 2.6}.get(z, 3.2)
        return (STYLE["road_primary"], w, w + 1.4 if z >= 11 else 0.0)
    if cls == "secondary":
        if z < 11:
            return None
        w = {11: 1.2, 12: 1.8}.get(z, 2.4)
        return (STYLE["road_secondary"], w, w + 1.2 if z >= 12 else 0.0)
    if cls == "minor":
        if z < 12:
            return None
        if z == 12:      # too thin for a cased white line: a quiet grey hairline
            return (STYLE["road_casing"], 0.8, 0.0)
        return (STYLE["road_minor"], 1.4, 2.4)
    if cls == "track":
        if z < 13:
            return None
        return (STYLE["road_track"], 1.0, 0.0)
    return None


def rail_width(z):
    if z < 9:
        return None
    return {9: 0.8, 10: 0.8, 11: 1.0, 12: 1.0}.get(z, 1.4)


def waterway_width(kind, z):
    if kind == "stream":
        return {12: 1.0, 13: 1.2}.get(z) if z >= 12 else None
    if z < 10:
        return None
    return {10: 1.0, 11: 1.4, 12: 2.0}.get(z, 2.8)


# --------------------------------------------------------------------------
# Raster

_FONT = None


def font():
    global _FONT
    if _FONT is None:
        _FONT = fontdata.load()
    return _FONT


def _glyph(table, ch):
    return table.get(ch) or table.get("?")


def text_width(text, variant):
    table = font()[variant]
    return sum(_glyph(table, ch)[0] for ch in text)


def text_mask(text, variant):
    """(width, height, top-above-baseline, rows) — rows are ints with bit k =
    pixel x = k (LSB is the leftmost pixel), built from the glyph bitmaps."""
    table = font()[variant]
    glyphs = [_glyph(table, ch) for ch in text]
    top = max((g[2] for g in glyphs if g[4]), default=0)
    bottom = max((g[4] - g[2] for g in glyphs if g[4]), default=0)
    height = top + bottom
    rows = [0] * height
    pen = 0.0
    width = 0
    for adv, left, gtop, w, h, grows in glyphs:
        x0 = int(round(pen + left))
        for r, bits in enumerate(grows):
            rev = int(format(bits, f"0{w}b")[::-1], 2) if w else 0
            rows[top - gtop + r] |= rev << max(0, x0)
        width = max(width, x0 + w)
        pen += adv
    return (max(width, int(round(pen))), height, top, rows)


def _dilate(rows, radius):
    out = []
    n = len(rows)
    for i in range(n):
        acc = 0
        for dy in range(-radius, radius + 1):
            j = i + dy
            if 0 <= j < n:
                acc |= rows[j]
        for dx in range(1, radius + 1):
            acc |= acc << dx | acc >> dx
        out.append(acc)
    return out


def _png_chunk(tag, data):
    return (struct.pack(">I", len(data)) + tag + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))


class Raster:
    """RGB canvas with even-odd polygon fill, thick polylines, box
    downsampling and PNG encoding — the whole drawing toolbox, in ~100 lines."""

    def __init__(self, width, height, background):
        self.w = width
        self.h = height
        self.buf = bytearray(bytes(background) * (width * height))

    @property
    def size(self):
        return (self.w, self.h)

    def get(self, x, y):
        i = (y * self.w + x) * 3
        return tuple(self.buf[i:i + 3])

    def fill_polygon(self, rings, color):
        """Even-odd scanline fill; rings are lists of (x, y) in pixel space,
        open or closed. Coordinates may lie far outside the canvas."""
        h, w = self.h, self.w
        # Bucket edges only over the rows the polygon actually spans: a road
        # segment quad touches a handful of rows, and allocating one bucket
        # per canvas row for each of thousands of quads was the render-time
        # bottleneck on dense urban tiles.
        ys = [p[1] for ring in rings for p in ring]
        if not ys:
            return
        lo = max(0, int(math.ceil(min(ys) - 0.5)))
        hi = min(h - 1, int(math.ceil(max(ys) - 0.5)) - 1)
        if hi < lo:
            return
        rows = [[] for _ in range(hi - lo + 1)]
        any_edge = False
        for ring in rings:
            n = len(ring)
            if n < 3:
                continue
            for i in range(n):
                x0, y0 = ring[i]
                x1, y1 = ring[(i + 1) % n]
                if y0 == y1:
                    continue
                if y0 > y1:
                    x0, y0, x1, y1 = x1, y1, x0, y0
                # rows whose centre (r + 0.5) lies in [y0, y1)
                r0 = max(lo, int(math.ceil(y0 - 0.5)))
                r1 = min(hi, int(math.ceil(y1 - 0.5)) - 1)
                if r1 < r0:
                    continue
                slope = (x1 - x0) / (y1 - y0)
                edge = (x0, y0, slope)
                for r in range(r0, r1 + 1):
                    rows[r - lo].append(edge)
                any_edge = True
        if not any_edge:
            return
        px = bytes(color)
        buf = self.buf
        for r in range(lo, hi + 1):
            edges = rows[r - lo]
            if not edges:
                continue
            yc = r + 0.5
            xs = sorted(x0 + (yc - y0) * slope for x0, y0, slope in edges)
            base = r * w
            for i in range(0, len(xs) - 1, 2):
                j0 = max(0, int(math.ceil(xs[i] - 0.5)))
                j1 = min(w - 1, int(math.ceil(xs[i + 1] - 0.5)) - 1)
                if j1 >= j0:
                    buf[(base + j0) * 3:(base + j1 + 1) * 3] = px * (j1 - j0 + 1)

    def stroke_polyline(self, points, width, color):
        half = width / 2.0
        if half <= 0:
            return
        joints = half >= 1.5
        for i in range(len(points) - 1):
            (x0, y0), (x1, y1) = points[i], points[i + 1]
            dx, dy = x1 - x0, y1 - y0
            length = math.hypot(dx, dy)
            if length < EPS:
                continue
            nx, ny = -dy / length * half, dx / length * half
            self.fill_polygon([[(x0 + nx, y0 + ny), (x1 + nx, y1 + ny),
                                (x1 - nx, y1 - ny), (x0 - nx, y0 - ny)]], color)
        if joints:
            for (x, y) in points:
                self.fill_polygon([[(x + half * math.cos(a), y + half * math.sin(a))
                                    for a in (k * math.pi / 4 for k in range(8))]], color)

    def _blit_rows(self, x0, y0, rows, color):
        w, h = self.w, self.h
        buf = self.buf
        px = bytes(color)
        for r, bits in enumerate(rows):
            y = y0 + r
            if y < 0 or y >= h or not bits:
                continue
            base = y * w
            while bits:
                lsb = bits & -bits
                x = x0 + lsb.bit_length() - 1
                if 0 <= x < w:
                    buf[(base + x) * 3:(base + x) * 3 + 3] = px
                bits ^= lsb

    def draw_text(self, x, y, text, variant, color, halo=None, halo_radius=2):
        """Text centred on x with its baseline at y (pixel space)."""
        width, height, top, rows = text_mask(text, variant)
        x0 = int(round(x - width / 2.0))
        y0 = int(round(y - top))
        if halo is not None:
            self._blit_rows(x0 - halo_radius, y0 - halo_radius,
                            [0] * halo_radius + _dilate(rows, halo_radius) + [0] * halo_radius, halo)
        self._blit_rows(x0, y0, rows, color)

    def fill_dot(self, x, y, radius, color):
        self.fill_polygon([[(x + radius * math.cos(a), y + radius * math.sin(a))
                            for a in (k * math.pi / 6 for k in range(12))]], color)

    def downsample(self, factor):
        w, h = self.w // factor, self.h // factor
        out = Raster(w, h, (0, 0, 0))
        src, dst = self.buf, out.buf
        sw = self.w
        f2 = factor * factor
        for y in range(h):
            for x in range(w):
                r = g = b = 0
                for dy in range(factor):
                    i = ((y * factor + dy) * sw + x * factor) * 3
                    for dx in range(factor):
                        r += src[i]; g += src[i + 1]; b += src[i + 2]
                        i += 3
                o = (y * w + x) * 3
                dst[o] = r // f2; dst[o + 1] = g // f2; dst[o + 2] = b // f2
        return out

    def to_png(self):
        stride = self.w * 3
        raw = b"".join(b"\x00" + bytes(self.buf[r * stride:(r + 1) * stride])
                       for r in range(self.h))
        ihdr = struct.pack(">IIBBBBB", self.w, self.h, 8, 2, 0, 0, 0)
        return (b"\x89PNG\r\n\x1a\n" + _png_chunk(b"IHDR", ihdr)
                + _png_chunk(b"IDAT", zlib.compress(raw, 6)) + _png_chunk(b"IEND", b""))


# --------------------------------------------------------------------------
# Geometry helpers (lon/lat plane, y up)

def signed_area(ring):
    a = 0.0
    n = len(ring)
    for i in range(n):
        x0, y0 = ring[i]
        x1, y1 = ring[(i + 1) % n]
        a += x0 * y1 - x1 * y0
    return a / 2.0


def rings_contain(rings, pt):
    """Even-odd point-in-polygon over a set of rings."""
    x, y = pt
    inside = False
    for ring in rings:
        n = len(ring)
        for i in range(n):
            x0, y0 = ring[i]
            x1, y1 = ring[(i + 1) % n]
            if (y0 > y) != (y1 > y):
                xi = x0 + (y - y0) * (x1 - x0) / (y1 - y0)
                if xi > x:
                    inside = not inside
    return inside


def _inside(rect, p):
    l, b, r, t = rect
    return l - EPS <= p[0] <= r + EPS and b - EPS <= p[1] <= t + EPS


def boundary_param(rect, x, y):
    """Position along the rectangle perimeter, clockwise from the top-left
    corner (top edge east, right edge south, bottom edge west, left edge north).
    Clockwise in a y-up frame keeps the interior on the RIGHT — the same side
    OSM puts the water on."""
    l, b, r, t = rect
    w, h = r - l, t - b
    d = [abs(y - t), abs(x - r), abs(y - b), abs(x - l)]
    edge = d.index(min(d))
    if edge == 0:
        v = x - l
    elif edge == 1:
        v = w + (t - y)
    elif edge == 2:
        v = w + h + (r - x)
    else:
        v = 2 * w + h + (y - b)
    p = 2 * (w + h)
    return 0.0 if v >= p - 1e-12 else v


def boundary_walk(rect, t_from, t_to):
    """Corner points passed when walking clockwise from t_from to t_to."""
    l, b, r, t = rect
    w, h = r - l, t - b
    p = 2 * (w + h)
    corners = [(w, (r, t)), (w + h, (r, b)), (2 * w + h, (l, b)), (p, (l, t))]
    span = (t_to - t_from) % p
    if span == 0:
        span = p
    out = []
    for tc, pt in corners:
        rel = (tc - t_from) % p
        if 0 < rel < span or (rel == 0 and tc == p and span == p):
            out.append((rel, pt))
    out.sort()
    return [pt for _, pt in out]


def _snap(rect, x, y):
    l, b, r, t = rect
    tol = 1e-9 * max(1.0, abs(r - l), abs(t - b))
    if abs(x - l) < tol: x = l
    if abs(x - r) < tol: x = r
    if abs(y - b) < tol: y = b
    if abs(y - t) < tol: y = t
    return (x, y)


def clip_polyline(line, rect):
    """Liang–Barsky clip of a polyline against a rectangle; returns the pieces
    inside, with exact boundary points where the line crosses the edges."""
    l, b, r, t = rect
    pieces = []
    cur = []

    def close():
        nonlocal cur
        if len(cur) >= 2:
            pieces.append(cur)
        cur = []

    for i in range(len(line) - 1):
        (x0, y0), (x1, y1) = line[i], line[i + 1]
        dx, dy = x1 - x0, y1 - y0
        t0, t1 = 0.0, 1.0
        ok = True
        for p, q in ((-dx, x0 - l), (dx, r - x0), (-dy, y0 - b), (dy, t - y0)):
            if p == 0:
                if q < 0:
                    ok = False
                    break
                continue
            u = q / p
            if p < 0:
                if u > t1:
                    ok = False
                    break
                if u > t0:
                    t0 = u
            else:
                if u < t0:
                    ok = False
                    break
                if u < t1:
                    t1 = u
        if not ok:
            close()
            continue
        pa = _snap(rect, x0 + t0 * dx, y0 + t0 * dy)
        pb = _snap(rect, x0 + t1 * dx, y0 + t1 * dy)
        if t0 > 0.0 or not cur:
            close()
            cur = [pa]
        cur.append(pb)
        if t1 < 1.0:
            close()
    close()
    return pieces


def stitch_chains(ways):
    """Joins coastline ways end-to-start by shared node id. Each way is a dict
    with 'nodes' (ids) and 'geometry' (list of (lon, lat))."""
    by_start = {}
    for idx, w in enumerate(ways):
        if len(w["nodes"]) >= 2:
            by_start.setdefault(w["nodes"][0], []).append(idx)
    ends = {w["nodes"][-1] for w in ways if len(w["nodes"]) >= 2}
    used = set()
    chains = []

    def walk(start_idx):
        chain = list(ways[start_idx]["geometry"])
        used.add(start_idx)
        cur = start_idx
        while True:
            nxt = None
            for cand in by_start.get(ways[cur]["nodes"][-1], []):
                if cand not in used:
                    nxt = cand
                    break
            if nxt is None:
                break
            used.add(nxt)
            chain.extend(ways[nxt]["geometry"][1:])
            cur = nxt
        return chain

    # chain heads first (start node that no way ends at), then leftovers (loops)
    for idx, w in enumerate(ways):
        if idx not in used and len(w["nodes"]) >= 2 and w["nodes"][0] not in ends:
            chains.append(walk(idx))
    for idx, w in enumerate(ways):
        if idx not in used and len(w["nodes"]) >= 2:
            chains.append(walk(idx))
    return chains


def _same(p, q):
    return abs(p[0] - q[0]) < 1e-7 and abs(p[1] - q[1]) < 1e-7


def assemble_rings(members):
    """Closes multipolygon outer rings from member ways drawn in any direction.
    Unclosable leftovers are dropped."""
    pool = [list(m) for m in members if len(m) >= 2]
    rings = []
    while pool:
        ring = pool.pop(0)
        progress = True
        while not _same(ring[0], ring[-1]) and progress:
            progress = False
            for i, cand in enumerate(pool):
                if _same(cand[0], ring[-1]):
                    ring.extend(cand[1:]); pool.pop(i); progress = True; break
                if _same(cand[-1], ring[-1]):
                    ring.extend(reversed(cand[:-1])); pool.pop(i); progress = True; break
        if _same(ring[0], ring[-1]) and len(ring) >= 4:
            ring[-1] = ring[0]
            rings.append(ring)
    return rings


def sea_rings(chains, rect):
    """Coastline chains (land left, water right) → rings that describe the
    water inside `rect` under even-odd filling. Returns [] when there is no
    coastline at all (the extent is then treated as land)."""
    pieces = []
    inner_rings = []
    dropped = 0
    for chain in chains:
        if len(chain) < 2:
            continue
        closed = _same(chain[0], chain[-1])
        if closed and all(_inside(rect, p) for p in chain):
            inner_rings.append(chain)
            continue
        if closed:
            # open the ring at a point outside so the clipper sees plain pieces
            k = next((i for i, p in enumerate(chain[:-1]) if not _inside(rect, p)), 0)
            chain = chain[k:-1] + chain[:k + 1]
        for piece in clip_polyline(chain, rect):
            on_edge = lambda p: (abs(p[0] - rect[0]) < EPS or abs(p[0] - rect[2]) < EPS
                                 or abs(p[1] - rect[1]) < EPS or abs(p[1] - rect[3]) < EPS)
            if on_edge(piece[0]) and on_edge(piece[-1]):
                pieces.append(piece)
            else:
                dropped += 1
    if dropped:
        print(f"osmrender: {dropped} coastline piece(s) end inside the extent "
              f"(data gap) — ignored", file=sys.stderr)

    result = []
    if pieces:
        entries = [boundary_param(rect, *p[0]) for p in pieces]
        exits = [boundary_param(rect, *p[-1]) for p in pieces]
        perim = 2 * ((rect[2] - rect[0]) + (rect[3] - rect[1]))
        unused = set(range(len(pieces)))
        while unused:
            start = min(unused)
            unused.discard(start)
            poly = list(pieces[start])
            cur = start
            for _ in range(len(pieces) + 1):
                te = exits[cur]
                # the next entry clockwise after this exit
                best, best_rel = None, None
                for j, tj in enumerate(entries):
                    rel = (tj - te) % perim
                    if rel == 0 and j != cur:
                        rel = perim
                    if best is None or rel < best_rel:
                        best, best_rel = j, rel
                poly.extend(boundary_walk(rect, te, entries[best]))
                if best == start:
                    break
                if best not in unused:
                    break            # inconsistent data; close what we have
                unused.discard(best)
                poly.extend(pieces[best])
                cur = best
            result.append(poly)
    elif any(signed_area(r) > 0 for r in inner_rings):
        # islands only: the whole extent is sea
        l, b, r, t = rect
        result.append([(l, t), (r, t), (r, b), (l, b)])
    result.extend(inner_rings)
    return result


# --------------------------------------------------------------------------
# Overpass → features

class Feature:
    __slots__ = ("layer", "cls", "geom", "nodes", "bbox", "name", "ele")

    def __init__(self, layer, cls, geom, nodes=None, name=None, ele=None):
        self.layer = layer      # coastline | water | green | waterway | road | rail | label
        self.cls = cls          # road class, waterway kind, green kind or label class
        self.geom = geom        # line: [(lon,lat)…]; area: [ring, …]; label: [(lon,lat)]
        self.nodes = nodes
        self.name = name
        self.ele = ele
        pts = geom if layer != "water" and layer != "green" else [p for r in geom for p in r]
        xs = [p[0] for p in pts]
        ys = [p[1] for p in pts]
        self.bbox = (min(xs), min(ys), max(xs), max(ys))


class MapData:
    def __init__(self, features):
        self.features = features


def _geometry(el):
    return [(g["lon"], g["lat"]) for g in el.get("geometry") or [] if g]


def _area_kind(tags):
    if tags.get("natural") == "water":
        return ("water", "water")
    for key in ("natural", "landuse", "leisure"):
        kind = GREEN_TAGS.get((key, tags.get(key)))
        if kind:
            return ("green", kind)
    return None


def parse_overpass(js):
    features = []
    for el in js.get("elements", []):
        tags = el.get("tags") or {}
        if el.get("type") == "node":
            name = tags.get("name")
            if not name or el.get("lat") is None:
                continue
            cls = None
            if tags.get("place") in PLACE_CLASSES:
                cls = tags["place"]
            elif tags.get("natural") in NATURAL_LABELS:
                cls = tags["natural"]
            if cls:
                features.append(Feature("label", cls, [(el["lon"], el["lat"])],
                                        name=name, ele=tags.get("ele")))
            continue
        if el.get("type") == "way":
            pts = _geometry(el)
            if len(pts) < 2:
                continue
            if tags.get("natural") == "coastline":
                features.append(Feature("coastline", None, pts, el.get("nodes") or []))
                continue
            area = _area_kind(tags)
            if area and _same(pts[0], pts[-1]) and len(pts) >= 4:
                features.append(Feature(area[0], area[1], [pts], name=tags.get("name")))
                continue
            hw = tags.get("highway")
            if hw in ROAD_CLASS:
                features.append(Feature("road", ROAD_CLASS[hw], pts))
                continue
            ww = tags.get("waterway")
            if ww in WATERWAYS:
                features.append(Feature("waterway", ww, pts))
                continue
            if tags.get("railway") == "rail":
                features.append(Feature("rail", "rail", pts))
        elif el.get("type") == "relation" and tags.get("type") == "multipolygon":
            area = _area_kind(tags)
            if not area:
                continue
            outers = [_geometry(m) for m in el.get("members") or []
                      if m.get("type") == "way" and m.get("role", "outer") in ("outer", "")]
            rings = assemble_rings(outers)
            if rings:
                features.append(Feature(area[0], area[1], rings, name=tags.get("name")))
    return MapData(features)


# --------------------------------------------------------------------------
# Overpass fetch

DEFAULT_ENDPOINT = "https://overpass-api.de/api/interpreter"
# Tried in turn when the primary keeps answering 429/504; both serve the
# same data. Polite pacing between requests keeps us under the rate limits.
MIRRORS = ["https://overpass.kumi.systems/api/interpreter"]
REQUEST_PAUSE_S = 3
USER_AGENT = "norda-map-pack/1.0 (+https://github.com/aripdcom/norda)"

_MAJOR = "motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link|tertiary|tertiary_link"
_MINOR = "unclassified|residential|living_street|track|path"
_GREEN_LANDUSE = "forest|grass|meadow|recreation_ground|cemetery|orchard|vineyard"
_GREEN_LEISURE = "park|garden|nature_reserve|golf_course|pitch"


def overpass_query(bbox, group, timeout=180):
    l, b, r, t = bbox
    bb = f"({b},{l},{t},{r})"
    if group == "coast":
        body = f'way["natural"="coastline"]{bb};'
    elif group == "base":
        body = (
            f'way["natural"~"^(water|wood)$"]{bb};'
            f'way["landuse"~"^({_GREEN_LANDUSE})$"]{bb};'
            f'way["leisure"~"^({_GREEN_LEISURE})$"]{bb};'
            f'relation["type"="multipolygon"]["natural"~"^(water|wood)$"]{bb};'
            f'relation["type"="multipolygon"]["landuse"~"^({_GREEN_LANDUSE})$"]{bb};'
            f'relation["type"="multipolygon"]["leisure"~"^({_GREEN_LEISURE})$"]{bb};'
            f'way["waterway"~"^(river|canal|stream)$"]{bb};'
            f'way["highway"~"^({_MAJOR})$"]{bb};'
            f'way["railway"="rail"]{bb};'
            f'node["place"~"^(city|town|village|suburb|quarter|neighbourhood|hamlet|locality|island|sea)$"]["name"]{bb};'
            f'node["natural"~"^(peak|bay|strait|cape)$"]["name"]{bb};'
        )
    elif group == "minor":
        body = f'way["highway"~"^({_MINOR})$"]{bb};'
    else:
        raise ValueError(group)
    return f"[out:json][timeout:{timeout}];({body});out geom;"


def split_cells(bbox, max_deg=0.2):
    l, b, r, t = bbox
    nx = max(1, int(math.ceil((r - l) / max_deg)))
    ny = max(1, int(math.ceil((t - b) / max_deg)))
    cells = []
    for i in range(nx):
        for j in range(ny):
            cells.append((l + (r - l) * i / nx, b + (t - b) * j / ny,
                          l + (r - l) * (i + 1) / nx, b + (t - b) * (j + 1) / ny))
    return cells


def fetch(endpoint, query, retries=5, log=print):
    """POSTs the query; on 429/5xx or network errors it backs off and, from the
    second retry on, alternates with the mirrors."""
    delays = [10, 20, 40, 60, 90]
    endpoints = [endpoint] + [m for m in MIRRORS if m != endpoint]
    for attempt in range(retries + 1):
        target = endpoints[attempt % len(endpoints)] if attempt >= 2 else endpoint
        req = urllib.request.Request(
            target, data=urllib.parse.urlencode({"data": query}).encode(),
            headers={"User-Agent": USER_AGENT})
        try:
            with urllib.request.urlopen(req, timeout=300) as resp:
                js = json.loads(resp.read().decode("utf-8"))
            time.sleep(REQUEST_PAUSE_S)
            return js
        except urllib.error.HTTPError as e:
            if e.code in (429, 500, 502, 503, 504) and attempt < retries:
                log(f"overpass: HTTP {e.code} from {target}, retrying in {delays[attempt]} s")
                time.sleep(delays[attempt])
                continue
            raise
        except (urllib.error.URLError, TimeoutError, ValueError) as e:
            if attempt < retries:
                log(f"overpass: {e}, retrying in {delays[attempt]} s")
                time.sleep(delays[attempt])
                continue
            raise


def fetch_region(bbox, coast_bbox, endpoint=DEFAULT_ENDPOINT, minor=True, log=print):
    """Fetches everything the renderer needs, merged and de-duplicated into one
    Overpass-shaped document."""
    seen = set()
    elements = []

    def take(js):
        for el in js.get("elements", []):
            key = (el.get("type"), el.get("id"))
            if key not in seen:
                seen.add(key)
                elements.append(el)

    for cell in split_cells(coast_bbox, 1.0):      # coastline is light: big cells
        log(f"overpass: coastline {cell}")
        take(fetch(endpoint, overpass_query(cell, "coast"), log=log))
    groups = ["base"] + (["minor"] if minor else [])
    cells = split_cells(bbox, 0.2)
    for n, cell in enumerate(cells, 1):
        for group in groups:
            log(f"overpass: {group} cell {n}/{len(cells)} {tuple(round(v, 3) for v in cell)}")
            take(fetch(endpoint, overpass_query(cell, group), log=log))
    log(f"overpass: {len(elements)} elements")
    return {"version": 0.6, "generator": "norda fetch_region", "elements": elements}


# --------------------------------------------------------------------------
# Label placement

class LabelCandidate:
    __slots__ = ("priority", "name", "x", "y", "variant", "color", "symbol", "box")

    def __init__(self, priority, name, x, y, variant, color=None, symbol=None):
        self.priority = priority
        self.name = name
        self.x = x              # anchor, pixel space (2× tile)
        self.y = y
        self.variant = variant
        self.color = color
        self.symbol = symbol
        self.box = None

    def layout(self):
        """Text box in pixel space: below the anchor for symbols, centred otherwise."""
        width, height, top, _ = text_mask(self.name, self.variant)
        baseline = self.y + (top + 8) if self.symbol else self.y + top / 2.0
        pad = 4
        self.box = (self.x - width / 2.0 - pad, baseline - top - pad,
                    self.x + width / 2.0 + pad, baseline - top + height + pad)
        return baseline


def _overlaps(a, b):
    return not (a[2] < b[0] or a[0] > b[2] or a[3] < b[1] or a[1] > b[3])


def place_labels(candidates):
    """Greedy by priority then name: a label is placed only if its box is free.
    Deterministic, so neighbouring tiles agree on what they share."""
    placed = []
    for cand in sorted(candidates, key=lambda c: (c.priority, c.name, c.x, c.y)):
        cand.layout()
        if any(_overlaps(cand.box, p.box) for p in placed):
            continue
        placed.append(cand)
    return placed


# --------------------------------------------------------------------------
# Scene: features → tiles

def _bbox_overlaps(a, b):
    return not (a[2] < b[0] or a[0] > b[2] or a[3] < b[1] or a[1] > b[3])


class Scene:
    def __init__(self, data, bbox, minzoom, supersample=2):
        self.ss = supersample
        self.extent = coast_extent(bbox, minzoom)
        coast = [{"nodes": f.nodes, "geometry": f.geom}
                 for f in data.features if f.layer == "coastline"]
        self.sea = sea_rings(stitch_chains(coast), self.extent)
        self.green = [f for f in data.features if f.layer == "green"]
        self.water = [f for f in data.features if f.layer == "water"]
        self.waterways = [f for f in data.features if f.layer == "waterway"]
        self.roads = sorted((f for f in data.features if f.layer == "road"),
                            key=lambda f: ROAD_RANK[f.cls])
        self.rails = [f for f in data.features if f.layer == "rail"]
        self.labels = [f for f in data.features if f.layer == "label"]
        for f in self.water:
            if f.name:
                l, b, r, t = f.bbox
                self.labels.append(Feature("label", "water", [((l + r) / 2, (b + t) / 2)],
                                           name=f.name, ele=None))
                self.labels[-1].bbox = f.bbox

    def render_tile(self, z, x, y):
        ss = self.ss
        size = TILE * ss
        scale = 1 << z
        ox, oy = x, y

        def project(pts):
            return [((x_tile(lon, z) - ox) * size, (y_tile(lat, z) - oy) * size)
                    for lon, lat in pts]

        left, bottom, right, top = tile_bounds(z, x, y)
        pad_x, pad_y = (right - left) * 0.25, (top - bottom) * 0.25
        view = (left - pad_x, bottom - pad_y, right + pad_x, top + pad_y)

        r = Raster(size, size, STYLE["land"])
        if self.sea:
            r.fill_polygon([project(ring) for ring in self.sea], STYLE["sea"])
        for f in self.green:
            if _bbox_overlaps(f.bbox, view):
                r.fill_polygon([project(ring) for ring in f.geom], STYLE[f.cls])
        for f in self.water:
            if _bbox_overlaps(f.bbox, view):
                r.fill_polygon([project(ring) for ring in f.geom], STYLE["water"])
        for f in self.waterways:
            w = waterway_width(f.cls, z)
            if w and _bbox_overlaps(f.bbox, view):
                r.stroke_polyline(project(f.geom), w * ss, STYLE["waterway"])
        visible = []
        for f in self.roads:
            st = road_style(f.cls, z)
            if st and _bbox_overlaps(f.bbox, view):
                visible.append((f, st))
        for f, (color, width, casing) in visible:
            if casing > 0:
                r.stroke_polyline(project(f.geom), casing * ss, STYLE["road_casing"])
        for f, (color, width, casing) in visible:
            r.stroke_polyline(project(f.geom), width * ss, color)
        rw = rail_width(z)
        if rw:
            for f in self.rails:
                if _bbox_overlaps(f.bbox, view):
                    r.stroke_polyline(project(f.geom), rw * ss, STYLE["rail"])
        self._draw_labels(r, z, x, y, size, project, view)
        return r.downsample(ss).to_png() if ss > 1 else r.to_png()

    def _draw_labels(self, r, z, x, y, size, project, view):
        # Candidates from a window one tile wider than the tile itself so that
        # placement decisions agree across tile edges (same neighbours seen).
        left, bottom, right, top = tile_bounds(z, x, y)
        wide = (left - (right - left), bottom - (top - bottom),
                right + (right - left), top + (top - bottom))
        candidates = []
        for f in self.labels:
            st = label_style(f.cls, z)
            if st is None:
                continue
            lon, lat = f.geom[0]
            if not (wide[0] <= lon <= wide[2] and wide[1] <= lat <= wide[3]):
                continue
            if f.cls == "water":
                # a lake gets its name only when it is wide enough on screen
                bl, bb, br, bt = f.bbox
                if (x_tile(br, z) - x_tile(bl, z)) * TILE < 40:
                    continue
            variant, color, symbol = st
            px, py = project([(lon, lat)])[0]
            name = f.name if f.cls != "peak" or not f.ele else f"{f.name} ▲{f.ele}"
            candidates.append(LabelCandidate(LABEL_PRIORITY.get(f.cls, 20), name, px, py,
                                             variant, color, symbol))
        for cand in place_labels(candidates):
            if cand.box[2] < 0 or cand.box[0] > size or cand.box[3] < 0 or cand.box[1] > size:
                continue
            baseline = cand.layout()
            if cand.symbol == "dot":
                r.fill_dot(cand.x, cand.y, 4.0, STYLE["halo"])
                r.fill_dot(cand.x, cand.y, 2.6, cand.color)
            elif cand.symbol == "peak":
                r.fill_polygon([[(cand.x, cand.y - 6), (cand.x + 5.5, cand.y + 4), (cand.x - 5.5, cand.y + 4)]], cand.color)
            r.draw_text(cand.x, baseline, cand.name, cand.variant, cand.color, halo=STYLE["halo"])
