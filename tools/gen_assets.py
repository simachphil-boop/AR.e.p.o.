#!/usr/bin/env python3
"""Генерирует 3D-модели (GLB) и звуки (WAV) для AR.E.P.O. Quest.

Запуск: python3 tools/gen_assets.py  (нужен numpy)
"""
import json, math, os, struct, wave
import numpy as np

ROOT = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'assets')
MODELS = os.path.join(ROOT, 'models')
AUDIO = os.path.join(ROOT, 'audio')
os.makedirs(MODELS, exist_ok=True)
os.makedirs(AUDIO, exist_ok=True)

# ====================== геометрия ======================

def lathe(profile, seg=24, caps=True):
    """Тело вращения: профиль [(r, y), ...] снизу вверх."""
    P, N = [], []
    pr = list(zip(profile[0::2], profile[1::2]))
    for i in range(len(pr) - 1):
        r0, y0 = pr[i]; r1, y1 = pr[i + 1]
        dr, dy = r1 - r0, y1 - y0
        l = max(1e-6, math.hypot(dr, dy))
        nr, ny = dy / l, -dr / l
        for j in range(seg):
            a0 = 2 * math.pi * j / seg; a1 = 2 * math.pi * (j + 1) / seg
            c0, s0, c1, s1 = math.cos(a0), math.sin(a0), math.cos(a1), math.sin(a1)
            v = [(r0 * c0, y0, r0 * s0, c0), (r1 * c0, y1, r1 * s0, c0), (r1 * c1, y1, r1 * s1, c1),
                 (r0 * c0, y0, r0 * s0, c0), (r1 * c1, y1, r1 * s1, c1), (r0 * c1, y0, r0 * s1, c1)]
            ss = [s0, s0, s1, s0, s1, s1]
            for (x, y, z, c), s in zip(v, ss):
                P.append((x, y, z)); N.append((nr * c, ny, nr * s))
    if caps:
        rb, yb = pr[0]; rt, yt = pr[-1]
        for j in range(seg):
            a0 = 2 * math.pi * j / seg; a1 = 2 * math.pi * (j + 1) / seg
            if rb > 0:
                for p in [(0, yb, 0), (rb * math.cos(a1), yb, rb * math.sin(a1)), (rb * math.cos(a0), yb, rb * math.sin(a0))]:
                    P.append(p); N.append((0, -1, 0))
            if rt > 0:
                for p in [(0, yt, 0), (rt * math.cos(a0), yt, rt * math.sin(a0)), (rt * math.cos(a1), yt, rt * math.sin(a1))]:
                    P.append(p); N.append((0, 1, 0))
    return np.array(P, np.float32), np.array(N, np.float32)


def tri_flat(tris):
    P, N = [], []
    for a, b, c in tris:
        a, b, c = np.array(a), np.array(b), np.array(c)
        n = np.cross(b - a, c - a); n = n / max(1e-9, np.linalg.norm(n))
        for p in (a, b, c):
            P.append(p); N.append(n)
    return np.array(P, np.float32), np.array(N, np.float32)


def box(w=1, h=1, d=1):
    x, y, z = w / 2, h / 2, d / 2
    q = lambda a, b, c, e: [(a, b, c), (a, c, e)]
    f = []
    f += q((-x, -y, z), (x, -y, z), (x, y, z), (-x, y, z))
    f += q((x, -y, -z), (-x, -y, -z), (-x, y, -z), (x, y, -z))
    f += q((x, -y, z), (x, -y, -z), (x, y, -z), (x, y, z))
    f += q((-x, -y, -z), (-x, -y, z), (-x, y, z), (-x, y, -z))
    f += q((-x, y, z), (x, y, z), (x, y, -z), (-x, y, -z))
    f += q((-x, -y, -z), (x, -y, -z), (x, -y, z), (-x, -y, z))
    return tri_flat(f)


def sphere(r=1, seg=20, rings=14):
    P, N = [], []
    def pt(i, j):
        th = math.pi * i / rings; ph = 2 * math.pi * j / seg
        n = (math.sin(th) * math.cos(ph), math.cos(th), math.sin(th) * math.sin(ph))
        return (n[0] * r, n[1] * r, n[2] * r), n
    for i in range(rings):
        for j in range(seg):
            a, b, c, d = pt(i, j), pt(i + 1, j), pt(i + 1, j + 1), pt(i, j + 1)
            for p, n in (a, c, b, a, d, c):
                P.append(p); N.append(n)
    return np.array(P, np.float32), np.array(N, np.float32)


def crystal(r, top, bottom, sides=6):
    t = []
    for j in range(sides):
        a0 = 2 * math.pi * j / sides; a1 = 2 * math.pi * (j + 1) / sides
        p0 = (r * math.cos(a0), 0, r * math.sin(a0)); p1 = (r * math.cos(a1), 0, r * math.sin(a1))
        t.append(((0, top, 0), p1, p0)); t.append(((0, -bottom, 0), p0, p1))
    return tri_flat(t)


def ring(ri, ro, seg=64):
    P, N = [], []
    for j in range(seg):
        a0 = 2 * math.pi * j / seg; a1 = 2 * math.pi * (j + 1) / seg
        c0, s0, c1, s1 = math.cos(a0), math.sin(a0), math.cos(a1), math.sin(a1)
        for p in [(ri * c0, 0, ri * s0), (ro * c1, 0, ro * s1), (ro * c0, 0, ro * s0), (ri * c0, 0, ri * s0), (ri * c1, 0, ri * s1), (ro * c1, 0, ro * s1)]:
            P.append(p); N.append((0, 1, 0))
    return np.array(P, np.float32), np.array(N, np.float32)


def cyl(r=1, h=1, seg=24):
    return lathe([r, 0, r, h], seg)


def xform(mesh, t=(0, 0, 0), s=(1, 1, 1), rx=0, ry=0, rz=0):
    P, N = mesh
    def R(ax, deg):
        a = math.radians(deg); c, s_ = math.cos(a), math.sin(a)
        if ax == 'x': return np.array([[1, 0, 0], [0, c, -s_], [0, s_, c]])
        if ax == 'y': return np.array([[c, 0, s_], [0, 1, 0], [-s_, 0, c]])
        return np.array([[c, -s_, 0], [s_, c, 0], [0, 0, 1]])
    M = R('y', ry) @ R('x', rx) @ R('z', rz)
    S = np.diag(s)
    P2 = (P @ S.T) @ M.T + np.array(t)
    Ninv = np.diag([1 / max(1e-9, v) for v in s])
    N2 = (N @ Ninv.T) @ M.T
    N2 = N2 / np.maximum(1e-9, np.linalg.norm(N2, axis=1, keepdims=True))
    return P2.astype(np.float32), N2.astype(np.float32)


# ====================== материалы ======================

def mat(color, metal=0.0, rough=0.6, emis=(0, 0, 0), alpha=1.0, blend=False):
    return {'c': list(color) + [alpha], 'm': metal, 'r': rough, 'e': list(emis), 'b': blend}

GOLD = mat((1.0, 0.72, 0.2), 1.0, 0.25)
WOOD = mat((0.42, 0.24, 0.11), 0.0, 0.7)
DARKWOOD = mat((0.28, 0.14, 0.06), 0.0, 0.7)
DARK = mat((0.03, 0.03, 0.04), 0.0, 0.9)
IVORY = mat((0.92, 0.88, 0.78), 0.0, 0.45)


# ====================== запись GLB ======================

def write_glb(path, parts, center=True):
    """parts: [(mesh, material)]. center=True — начало координат в центре габаритов."""
    allP = np.concatenate([m[0] for m, _ in parts])
    lo, hi = allP.min(0), allP.max(0)
    off = -(lo + hi) / 2 if center else np.array([0, 0, 0], np.float32)
    bin_ = bytearray()
    accessors, views, prims, mats = [], [], [], []
    mat_index = {}
    def add_view(data, target=34962):
        nonlocal bin_
        while len(bin_) % 4: bin_ += b'\0'
        o = len(bin_); bin_ += data
        views.append({'buffer': 0, 'byteOffset': o, 'byteLength': len(data), 'target': target})
        return len(views) - 1
    for mesh, m in parts:
        P = (mesh[0] + off).astype(np.float32); N = mesh[1].astype(np.float32)
        key = json.dumps(m, sort_keys=True)
        if key not in mat_index:
            md = {'pbrMetallicRoughness': {'baseColorFactor': m['c'], 'metallicFactor': m['m'], 'roughnessFactor': m['r']},
                  'doubleSided': True}
            if any(m['e']): md['emissiveFactor'] = [min(1.0, v) for v in m['e']]
            if m['b']: md['alphaMode'] = 'BLEND'
            mat_index[key] = len(mats); mats.append(md)
        vp = add_view(P.tobytes()); vn = add_view(N.tobytes())
        accessors.append({'bufferView': vp, 'componentType': 5126, 'count': len(P), 'type': 'VEC3',
                          'min': P.min(0).tolist(), 'max': P.max(0).tolist()})
        accessors.append({'bufferView': vn, 'componentType': 5126, 'count': len(N), 'type': 'VEC3'})
        prims.append({'attributes': {'POSITION': len(accessors) - 2, 'NORMAL': len(accessors) - 1}, 'material': mat_index[key]})
    while len(bin_) % 4: bin_ += b'\0'
    gltf = {'asset': {'version': '2.0', 'generator': 'arepo-gen'}, 'scene': 0, 'scenes': [{'nodes': [0]}],
            'nodes': [{'mesh': 0, 'name': os.path.basename(path)}], 'meshes': [{'primitives': prims}],
            'materials': mats, 'accessors': accessors, 'bufferViews': views, 'buffers': [{'byteLength': len(bin_)}]}
    js = json.dumps(gltf, separators=(',', ':')).encode()
    while len(js) % 4: js += b' '
    out = struct.pack('<III', 0x46546C67, 2, 12 + 8 + len(js) + 8 + len(bin_))
    out += struct.pack('<II', len(js), 0x4E4F534A) + js
    out += struct.pack('<II', len(bin_), 0x004E4942) + bytes(bin_)
    with open(path, 'wb') as f: f.write(out)
    size = (hi - lo)
    return [round(float(v), 3) for v in size]


B, S, C = box(), sphere(), cyl()
sizes = {}

def item(name, parts):
    sizes[name] = write_glb(os.path.join(MODELS, name + '.glb'), parts, center=True)

porcelain = mat((0.25, 0.45, 0.95), 0.0, 0.2)
item('vase', [(lathe([0.055, 0, 0.08, 0.04, 0.1, 0.12, 0.085, 0.2, 0.042, 0.26, 0.05, 0.3]), porcelain),
              (xform(C, (0, 0.115, 0), (0.102, 0.014, 0.102)), GOLD)])
item('trophy', [(lathe([0.07, 0, 0.07, 0.02, 0.02, 0.035, 0.016, 0.13, 0.06, 0.16, 0.085, 0.25, 0.08, 0.27]), GOLD),
                (xform(B, (0.095, 0.2, 0), (0.025, 0.07, 0.012)), GOLD), (xform(B, (-0.095, 0.2, 0), (0.025, 0.07, 0.012)), GOLD)])
item('clock', [(xform(B, (0, 0.11, 0), (0.18, 0.22, 0.09)), WOOD),
               (xform(C, (0, 0.14, 0.045), (0.065, 0.01, 0.065), rx=90), mat((0.95, 0.92, 0.8), 0, 0.4)),
               (xform(C, (0, 0.14, 0.043), (0.074, 0.008, 0.074), rx=90), GOLD),
               (xform(B, (0, 0.16, 0.056), (0.006, 0.045, 0.004)), DARK), (xform(B, (0.015, 0.14, 0.056), (0.035, 0.006, 0.004)), DARK)])
item('globe', [(xform(C, (0, 0, 0), (0.07, 0.02, 0.07)), WOOD), (xform(C, (0, 0, 0), (0.012, 0.12, 0.012)), GOLD),
               (xform(S, (0, 0.2, 0), (0.09, 0.09, 0.09)), mat((0.12, 0.42, 0.8), 0, 0.35)),
               (xform(S, (0.03, 0.23, 0.05), (0.05, 0.04, 0.045)), mat((0.25, 0.65, 0.25), 0, 0.6)),
               (xform(S, (-0.05, 0.17, -0.03), (0.04, 0.035, 0.05)), mat((0.25, 0.65, 0.25), 0, 0.6))])
crys = mat((0.7, 0.3, 1.0), 0.2, 0.1, emis=(0.3, 0.06, 0.5))
item('crystal', [(xform(crystal(0.07, 0.22, 0.05), (0, 0.05, 0)), crys), (xform(crystal(0.035, 0.11, 0.03), (0.06, 0.03, 0), rz=-25), crys),
                 (xform(crystal(0.03, 0.09, 0.025), (-0.05, 0.02, 0.02), rz=30), crys)])
item('bars', [(xform(B, (-0.04, 0.025, 0), (0.07, 0.05, 0.15)), GOLD), (xform(B, (0.04, 0.025, 0), (0.07, 0.05, 0.15)), GOLD),
              (xform(B, (0, 0.075, 0), (0.07, 0.05, 0.15), ry=90), GOLD)])
item('statuette', [(xform(B, (0, 0.015, 0), (0.1, 0.03, 0.1)), mat((0.3, 0.3, 0.32), 0.1, 0.4)),
                   (lathe([0.045, 0.03, 0.035, 0.12, 0.025, 0.2, 0.01, 0.22]), IVORY), (xform(S, (0, 0.245, 0), (0.035, 0.035, 0.035)), IVORY)])
item('orb', [(xform(C, (0, 0, 0), (0.06, 0.035, 0.06)), mat((0.05, 0.05, 0.06), 0.8, 0.3)),
             (xform(S, (0, 0.11, 0), (0.08, 0.08, 0.08)), mat((0.2, 0.95, 1.0), 0, 0.05, emis=(0.15, 0.7, 0.85)))])
item('lamp', [(xform(C, (0, 0, 0), (0.06, 0.02, 0.06)), GOLD), (xform(C, (0, 0, 0), (0.01, 0.2, 0.01)), GOLD),
              (lathe([0.1, 0.16, 0.05, 0.28], 24), mat((0.95, 0.75, 0.45), 0, 0.6, emis=(0.6, 0.38, 0.1)))])
item('chest', [(xform(B, (0, 0.055, 0), (0.22, 0.11, 0.14)), WOOD), (xform(B, (0, 0.125, 0), (0.23, 0.035, 0.15)), DARKWOOD),
               (xform(B, (0, 0.1, 0.072), (0.03, 0.04, 0.01)), GOLD), (xform(B, (0.09, 0.07, 0), (0.012, 0.13, 0.145)), GOLD),
               (xform(B, (-0.09, 0.07, 0), (0.012, 0.13, 0.145)), GOLD)])
item('painting', [(xform(B, (0, 0.11, 0), (0.3, 0.22, 0.025), rx=-10), GOLD),
                  (xform(B, (0, 0.11, 0.004), (0.255, 0.175, 0.025), rx=-10), mat((0.12, 0.3, 0.27), 0, 0.8)),
                  (xform(S, (0.05, 0.14, 0.018), (0.03, 0.03, 0.01)), mat((0.95, 0.75, 0.3), 0, 0.6))])
item('skull', [(xform(S, (0, 0.1, 0), (0.075, 0.07, 0.085)), GOLD), (xform(B, (0, 0.035, 0.035), (0.07, 0.04, 0.05)), GOLD),
               (xform(S, (0.028, 0.1, 0.07), (0.02, 0.022, 0.012)), DARK), (xform(S, (-0.028, 0.1, 0.07), (0.02, 0.022, 0.012)), DARK)])
item('piano', [(xform(B, (0, 0.06, 0), (0.34, 0.12, 0.16)), mat((0.03, 0.03, 0.035), 0.1, 0.15)),
               (xform(B, (0, 0.125, 0.045), (0.3, 0.012, 0.06)), mat((0.95, 0.95, 0.92), 0, 0.3)),
               (xform(B, (0, 0.17, -0.06), (0.34, 0.1, 0.04)), mat((0.03, 0.03, 0.035), 0.1, 0.15)),
               (xform(B, (0, 0.13, 0.02), (0.012, 0.005, 0.03)), DARK)])

# ---- монстры (начало координат у ног) ----
red_eye = mat((1, 0.1, 0.05), 0, 0.3, emis=(1, 0.1, 0.05))
shadow_m = mat((0.02, 0.02, 0.03), 0, 0.95)
write_glb(os.path.join(MODELS, 'shadow.glb'), [
    (lathe([0.32, 0, 0.25, 0.5, 0.17, 1.05, 0.15, 1.3, 0.13, 1.42, 0.1, 1.6, 0.04, 1.72], 20), shadow_m),
    (xform(S, (0, 1.55, 0), (0.13, 0.16, 0.13)), shadow_m),
    (xform(B, (0.2, 1.15, 0.12), (0.06, 0.6, 0.06), rx=-35, rz=15), shadow_m),
    (xform(B, (-0.2, 1.15, 0.12), (0.06, 0.6, 0.06), rx=-35, rz=-15), shadow_m),
    (xform(S, (0.05, 1.58, 0.115), (0.022, 0.016, 0.01)), red_eye),
    (xform(S, (-0.05, 1.58, 0.115), (0.022, 0.016, 0.01)), red_eye)], center=False)
stone = mat((0.7, 0.68, 0.64), 0, 0.85)
write_glb(os.path.join(MODELS, 'statue.glb'), [
    (lathe([0.24, 0, 0.21, 0.7, 0.16, 1.2, 0.12, 1.4], 18), stone),
    (xform(S, (0, 1.53, 0), (0.12, 0.145, 0.12)), stone),
    (xform(B, (0.17, 1.27, 0.2), (0.07, 0.07, 0.46), rx=-15), stone),
    (xform(B, (-0.17, 1.27, 0.2), (0.07, 0.07, 0.46), rx=-15), stone),
    (xform(S, (0.045, 1.56, 0.105), (0.026, 0.032, 0.012)), DARK),
    (xform(S, (-0.045, 1.56, 0.105), (0.026, 0.032, 0.012)), DARK),
    (xform(B, (0, 1.46, 0.11), (0.08, 0.025, 0.012)), DARK)], center=False)
crawl = mat((0.32, 0.07, 0.05), 0, 0.5)
legs = [(xform(B, (0, 0.15, 0), (0.035, 0.035, 0.45), ry=30 + i * 60, rx=25), crawl) for i in range(6)]
write_glb(os.path.join(MODELS, 'jumper.glb'), [
    (xform(S, (0, 0.25, 0), (0.2, 0.14, 0.24)), crawl), (xform(S, (0, 0.3, 0.2), (0.1, 0.09, 0.1)), crawl),
    (xform(S, (0.04, 0.33, 0.29), (0.018, 0.018, 0.01)), red_eye), (xform(S, (-0.04, 0.33, 0.29), (0.018, 0.018, 0.01)), red_eye)] + legs, center=False)

# ---- точка сдачи, луч захвата, осколок ----
write_glb(os.path.join(MODELS, 'zone.glb'), [
    (ring(0.5, 0.58), mat((0.2, 1, 0.45), 0, 0.5, emis=(0.15, 0.9, 0.35))),
    (ring(0.0, 0.5), mat((0.2, 1, 0.45), 0, 0.5, emis=(0.05, 0.3, 0.12), alpha=0.25, blend=True)),
    (lathe([0.56, 0, 0.5, 2.4], 40, caps=False), mat((0.2, 1, 0.45), 0, 0.5, emis=(0.05, 0.4, 0.15), alpha=0.12, blend=True)),
    (xform(crystal(0.07, 0.12, 0.12, 4), (0, 1.7, 0)), mat((0.2, 1, 0.45), 0, 0.3, emis=(0.2, 1, 0.45)))], center=False)
write_glb(os.path.join(MODELS, 'zone_red.glb'), [
    (ring(0.5, 0.58), mat((1, 0.35, 0.2), 0, 0.5, emis=(0.9, 0.25, 0.1))),
    (ring(0.0, 0.5), mat((1, 0.35, 0.2), 0, 0.5, emis=(0.3, 0.08, 0.03), alpha=0.25, blend=True)),
    (lathe([0.56, 0, 0.5, 2.4], 40, caps=False), mat((1, 0.35, 0.2), 0, 0.5, emis=(0.35, 0.1, 0.04), alpha=0.12, blend=True)),
    (xform(crystal(0.07, 0.12, 0.12, 4), (0, 1.7, 0)), mat((1, 0.35, 0.2), 0, 0.3, emis=(1, 0.35, 0.2)))], center=False)
# луч: цилиндр вдоль +Z длиной 1
write_glb(os.path.join(MODELS, 'beam.glb'), [
    (xform(cyl(0.006, 1.0, 10), rx=90), mat((1, 0.85, 0.3), 0, 0.5, emis=(1, 0.75, 0.2), alpha=0.75, blend=True))], center=False)
write_glb(os.path.join(MODELS, 'pointer.glb'), [
    (xform(cyl(0.003, 1.0, 8), rx=90), mat((0.6, 0.9, 1), 0, 0.5, emis=(0.4, 0.8, 1), alpha=0.45, blend=True))], center=False)
write_glb(os.path.join(MODELS, 'shard.glb'), [(box(0.03, 0.03, 0.03), mat((0.9, 0.9, 0.95), 0.3, 0.3))], center=True)
write_glb(os.path.join(MODELS, 'marker.glb'), [(xform(crystal(0.03, 0.05, 0.05, 4), (0, 0, 0)), mat((1, 0.8, 0.2), 0, 0.3, emis=(1, 0.75, 0.15)))], center=True)

with open(os.path.join(os.path.dirname(__file__), 'item_sizes.json'), 'w') as f:
    json.dump(sizes, f, indent=1)
print('models:', sizes)

# ====================== звуки ======================
SR = 44100

def save(name, x, vol=0.9):
    x = np.asarray(x, np.float64)
    pk = np.max(np.abs(x)) or 1
    x = x / pk * vol
    with wave.open(os.path.join(AUDIO, name + '.wav'), 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((x * 32767).astype('<i2').tobytes())

def t_(sec): return np.arange(int(SR * sec)) / SR
def env(n, a=0.005, dec=None):
    t = np.arange(n) / SR
    e = np.minimum(1, t / max(a, 1e-4))
    if dec: e = e * np.exp(-t / dec)
    return e
def tone(f0, sec, f1=None, kind='sine', dec=None, a=0.005):
    t = t_(sec); f1 = f1 or f0
    f = f0 * (f1 / f0) ** (t / sec)
    ph = 2 * np.pi * np.cumsum(f) / SR
    if kind == 'sine': w = np.sin(ph)
    elif kind == 'square': w = np.sign(np.sin(ph)) * 0.6
    elif kind == 'saw': w = 2 * ((ph / (2 * np.pi)) % 1) - 1
    else: w = 2 * np.abs(2 * ((ph / (2 * np.pi)) % 1) - 1) - 1
    return w * env(len(t), a, dec or sec / 3)
def noise(sec, dec=None):
    n = int(SR * sec); return (np.random.rand(n) * 2 - 1) * env(n, 0.002, dec or sec / 3)
def lp(x, fc):
    a = math.exp(-2 * math.pi * fc / SR); y = np.zeros_like(x); s = 0.0
    for i in range(len(x)): s = (1 - a) * x[i] + a * s; y[i] = s
    return y
def hp(x, fc): return x - lp(x, fc)
def mix(*xs):
    n = max(len(x) for x in xs); y = np.zeros(n)
    for x in xs: y[:len(x)] += x
    return y
def at(x, sec):
    return np.concatenate([np.zeros(int(SR * sec)), x])

np.random.seed(7)
save('grab', tone(420, 0.14, 900, 'square'), 0.5)
save('release', tone(700, 0.14, 320, 'square'), 0.45)
save('hit', mix(hp(noise(0.12, 0.03), 1800), tone(1900, 0.06, kind='tri') * 0.4))
save('break', mix(hp(noise(0.7, 0.15), 2500), *[at(tone(2000 + np.random.rand() * 4000, 0.1 + np.random.rand() * 0.2, dec=0.05) * 0.4, np.random.rand() * 0.25) for _ in range(9)]))
save('thud', mix(tone(130, 0.25, 45, dec=0.07), lp(noise(0.1, 0.03), 600) * 0.6))
save('coins', mix(*[at(tone(f, 0.3, kind='square', dec=0.12) * 0.5, i * 0.08) for i, f in enumerate([988, 1319, 1568, 1976, 2637])]))
save('start', mix(tone(110, 1.4, 55, 'saw', dec=0.6) * 0.5, at(tone(880, 0.5, dec=0.2), 0.3)))
save('hurt', mix(lp(noise(0.4, 0.12), 500), tone(90, 0.4, 45, 'saw', dec=0.15) * 0.7))
save('spotted', mix(lp(tone(98, 1.4, 92, 'saw', dec=0.7), 900), lp(tone(104, 1.4, 99, 'saw', dec=0.7), 900), lp(noise(1.2, 0.5), 300) * 0.5))
save('scrape', lp(hp(noise(0.5, 0.2), 300), 1400))
save('leap', mix(hp(noise(0.35, 0.1), 2500), tone(300, 0.3, 900, 'saw') * 0.5))
save('shock', mix(tone(1400, 0.4, 120, 'saw', dec=0.15), hp(noise(0.35, 0.1), 3500) * 0.6))
save('heal', mix(*[at(tone(f, 0.35, dec=0.15), i * 0.09) for i, f in enumerate([523, 659, 784])]))
save('buy', mix(tone(784, 0.1, kind='square'), at(tone(1047, 0.2, kind='square'), 0.09)), 0.5)
save('blip', tone(1200, 0.07), 0.4)
save('heartbeat', mix(tone(62, 0.15, 40, dec=0.05), at(tone(58, 0.15, 38, dec=0.05) * 0.8, 0.18)))
save('growl', lp(mix(noise(0.9, 0.4), tone(70, 0.9, 55, 'saw', dec=0.4) * 0.5), 320))
save('detector', tone(1600, 0.06), 0.35)
save('dead', mix(tone(220, 1.8, 40, 'saw', dec=0.8), lp(noise(1.5, 0.6), 400)))
save('extract', mix(*[at(tone(f, 0.5, kind='tri', dec=0.25), i * 0.1) for i, f in enumerate([523, 659, 784, 1047])]))
# монстр: зацикленное дыхание/гул
t = t_(4.0)
breath = lp(np.random.rand(len(t)) * 2 - 1, 500) * (0.5 + 0.5 * np.sin(2 * np.pi * 0.5 * t)) ** 2
hum = np.sin(2 * np.pi * 55 * t + 0.6 * np.sin(2 * np.pi * 0.3 * t)) * 0.35
save('monster_loop', breath + hum, 0.8)
# фон: тревожный гул (бесшовная петля 8 с)
t = t_(8.0)
amb = (np.sin(2 * np.pi * 41.25 * t) * 0.5 + np.sin(2 * np.pi * 61.875 * t) * 0.25 * (0.5 + 0.5 * np.sin(2 * np.pi * t / 8)) +
       lp(np.random.rand(len(t)) * 2 - 1, 200) * 0.6)
save('ambient', amb, 0.6)
print('audio ok')
