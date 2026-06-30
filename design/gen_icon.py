#!/usr/bin/env python3
# Genera l'icona GRBL: SVG sorgente, VectorDrawable adaptive (API 26+), PNG legacy (API 23-25).
import os, math
from PIL import Image, ImageDraw
import numpy as np

PROJ = r"C:\Users\daniele ci\AndroidStudioProjects\grblcontroller"
RES = os.path.join(PROJ, "app", "src", "main", "res")
DESIGN = os.path.join(PROJ, "design")

# ---- Colori (modificabili) ----
BG      = "#000000"
ARROW   = "#00E5FF"
TP0     = "#0D47A1"   # toolpath gradiente: inizio (centro)
TP1     = "#2979FF"   # toolpath gradiente: fine (esterno)

R_CORNER = 6.0        # raggio arrotondamento angoli toolpath (cornering)
STROKE   = 6.0        # spessore toolpath (in coord tile 320)

# ---- Geometria in coord viewBox originale (tile = orig - (180,24)) ----
SHIFT = (180, 24)
def tile(p): return (p[0]-SHIFT[0], p[1]-SHIFT[1])

MAIN = [(322,202),(322,166),(340,184),(358,166),(358,202),(358,220),(304,220),
        (304,150),(376,150),(376,236),(288,236),(288,134),(392,134),(392,252),
        (272,252),(272,118),(408,118),(408,268),(256,268),(256,102),(424,102),
        (424,284),(240,284),(240,86),(440,86)]
OVER_A = [(376,150),(376,236),(288,236),(288,134),(392,134)]
OVER_B = [(408,118),(408,268),(256,268),(256,102),(424,102)]
ARROWS = [
    [(340,76),(289,130),(391,130)],
    [(340,292),(289,238),(391,238)],
    [(232,184),(286,133),(286,235)],
    [(448,184),(394,133),(394,235)],
]
G0, G1 = (256,100), (424,284)   # asse gradiente (orig)

MAIN  = [tile(p) for p in MAIN]
OVER_A= [tile(p) for p in OVER_A]
OVER_B= [tile(p) for p in OVER_B]
ARROWS= [[tile(p) for p in tri] for tri in ARROWS]
G0, G1 = tile(G0), tile(G1)

# ---- Arrotondamento angoli -> (d-string SVG, polilinea densa) ----
def round_path(pts, r):
    d = [f"M{pts[0][0]:.2f},{pts[0][1]:.2f}"]
    dense = [pts[0]]
    for i in range(1, len(pts)-1):
        p0,p1,p2 = pts[i-1],pts[i],pts[i+1]
        ux,uy = p1[0]-p0[0], p1[1]-p0[1]; Lin = math.hypot(ux,uy); ux,uy = ux/Lin,uy/Lin
        vx,vy = p2[0]-p1[0], p2[1]-p1[1]; Lout= math.hypot(vx,vy); vx,vy = vx/Lout,vy/Lout
        rr = min(r, Lin/2, Lout/2)
        A = (p1[0]-ux*rr, p1[1]-uy*rr)
        B = (p1[0]+vx*rr, p1[1]+vy*rr)
        d.append(f"L{A[0]:.2f},{A[1]:.2f}")
        d.append(f"Q{p1[0]:.2f},{p1[1]:.2f} {B[0]:.2f},{B[1]:.2f}")
        dense.append(A)
        for k in range(1,9):
            t=k/8
            qx=(1-t)**2*A[0]+2*(1-t)*t*p1[0]+t*t*B[0]
            qy=(1-t)**2*A[1]+2*(1-t)*t*p1[1]+t*t*B[1]
            dense.append((qx,qy))
    d.append(f"L{pts[-1][0]:.2f},{pts[-1][1]:.2f}")
    dense.append(pts[-1])
    return " ".join(d), dense

main_d, main_dense   = round_path(MAIN, R_CORNER)
overA_d, overA_dense = round_path(OVER_A, R_CORNER)
overB_d, overB_dense = round_path(OVER_B, R_CORNER)

def transform(pts, f, ox, oy):
    return [(p[0]*f+ox, p[1]*f+oy) for p in pts]

# ============ 1) SVG sorgente (tile 320, ricolorabile) ============
def svg_poly(pts): return " ".join(f"{x:.1f},{y:.1f}" for x,y in pts)
os.makedirs(DESIGN, exist_ok=True)
svg = f'''<svg width="320" height="320" viewBox="0 0 320 320" xmlns="http://www.w3.org/2000/svg">
  <!-- Icona GRBL Machining. Colori in cima al file per ricolorare facilmente. -->
  <defs>
    <linearGradient id="toolpath" gradientUnits="userSpaceOnUse"
      x1="{G0[0]:.1f}" y1="{G0[1]:.1f}" x2="{G1[0]:.1f}" y2="{G1[1]:.1f}">
      <stop offset="0" stop-color="{TP0}"/>
      <stop offset="1" stop-color="{TP1}"/>
    </linearGradient>
  </defs>
  <rect x="0" y="0" width="320" height="320" rx="60" fill="{BG}"/>
  <path d="{main_d}" fill="none" stroke="url(#toolpath)" stroke-width="{STROKE}"
    stroke-linecap="round" stroke-linejoin="round"/>
  <polygon points="{svg_poly(ARROWS[0])}" fill="{ARROW}"/>
  <polygon points="{svg_poly(ARROWS[1])}" fill="{ARROW}"/>
  <polygon points="{svg_poly(ARROWS[2])}" fill="{ARROW}"/>
  <polygon points="{svg_poly(ARROWS[3])}" fill="{ARROW}"/>
  <path d="{overA_d}" fill="none" stroke="url(#toolpath)" stroke-width="{STROKE}"
    stroke-linecap="round" stroke-linejoin="round"/>
  <path d="{overB_d}" fill="none" stroke="url(#toolpath)" stroke-width="{STROKE}"
    stroke-linecap="round" stroke-linejoin="round"/>
</svg>
'''
open(os.path.join(DESIGN,"ic_launcher.svg"),"w",encoding="utf-8").write(svg)

# ============ 2) VectorDrawable adaptive foreground (108, safe zone) ============
F = 0.32; OFF = 2.8   # tile(320) -> 108dp con margine safe-zone
def vd_path(pts, r):
    tp = transform(pts, F, OFF, OFF)
    d,_ = round_path(tp, r*F)
    return d
fg_main  = vd_path(MAIN,  R_CORNER)
fg_overA = vd_path(OVER_A,R_CORNER)
fg_overB = vd_path(OVER_B,R_CORNER)
gA0 = (G0[0]*F+OFF, G0[1]*F+OFF); gA1 = (G1[0]*F+OFF, G1[1]*F+OFF)
def vd_tri(tri):
    p=transform(tri,F,OFF,OFF)
    return f"M{p[0][0]:.2f},{p[0][1]:.2f} L{p[1][0]:.2f},{p[1][1]:.2f} L{p[2][0]:.2f},{p[2][1]:.2f} Z"
tri_d = " ".join(vd_tri(t) for t in ARROWS)
sw = STROKE*F
def hexa(c): return "#FF"+c.lstrip("#")
grad = f'''    <aapt:attr name="android:strokeColor">
      <gradient android:type="linear"
        android:startX="{gA0[0]:.2f}" android:startY="{gA0[1]:.2f}"
        android:endX="{gA1[0]:.2f}" android:endY="{gA1[1]:.2f}">
        <item android:offset="0" android:color="{hexa(TP0)}"/>
        <item android:offset="1" android:color="{hexa(TP1)}"/>
      </gradient>
    </aapt:attr>'''
fg = f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
  <path android:pathData="{fg_main}" android:strokeWidth="{sw:.3f}"
    android:strokeLineCap="round" android:strokeLineJoin="round">
{grad}
  </path>
  <path android:pathData="{tri_d}" android:fillColor="{hexa(ARROW)}"/>
  <path android:pathData="{fg_overA}" android:strokeWidth="{sw:.3f}"
    android:strokeLineCap="round" android:strokeLineJoin="round">
{grad}
  </path>
  <path android:pathData="{fg_overB}" android:strokeWidth="{sw:.3f}"
    android:strokeLineCap="round" android:strokeLineJoin="round">
{grad}
  </path>
</vector>
'''
os.makedirs(os.path.join(RES,"drawable"),exist_ok=True)
open(os.path.join(RES,"drawable","ic_launcher_foreground.xml"),"w",encoding="utf-8").write(fg)

bg = f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
  <path android:fillColor="{hexa(BG)}" android:pathData="M0,0 L108,0 L108,108 L0,108 Z"/>
</vector>
'''
open(os.path.join(RES,"drawable","ic_launcher_background.xml"),"w",encoding="utf-8").write(bg)

# ============ 3) adaptive-icon anydpi-v26 ============
adaptive = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
</adaptive-icon>
'''
anydpi=os.path.join(RES,"mipmap-anydpi-v26"); os.makedirs(anydpi,exist_ok=True)
open(os.path.join(anydpi,"ic_launcher.xml"),"w",encoding="utf-8").write(adaptive)
open(os.path.join(anydpi,"ic_launcher_round.xml"),"w",encoding="utf-8").write(adaptive)

# ============ 3b) Splash: stessa grafica a piena scala (viewport 320) ============
# Nessuno sfondo nel vettore: lo sfondo nero lo mette la layer-list qui sotto.
sp_main  = round_path(MAIN,   R_CORNER)[0]
sp_overA = round_path(OVER_A, R_CORNER)[0]
sp_overB = round_path(OVER_B, R_CORNER)[0]
def sp_tri(t):
    return (f"M{t[0][0]:.2f},{t[0][1]:.2f} L{t[1][0]:.2f},{t[1][1]:.2f} "
            f"L{t[2][0]:.2f},{t[2][1]:.2f} Z")
sp_tris = " ".join(sp_tri(t) for t in ARROWS)
sp_grad = f'''    <aapt:attr name="android:strokeColor">
      <gradient android:type="linear"
        android:startX="{G0[0]:.2f}" android:startY="{G0[1]:.2f}"
        android:endX="{G1[0]:.2f}" android:endY="{G1[1]:.2f}">
        <item android:offset="0" android:color="{hexa(TP0)}"/>
        <item android:offset="1" android:color="{hexa(TP1)}"/>
      </gradient>
    </aapt:attr>'''
splash = f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="320dp" android:height="320dp"
    android:viewportWidth="320" android:viewportHeight="320">
  <path android:pathData="{sp_main}" android:strokeWidth="{STROKE:.1f}"
    android:strokeLineCap="round" android:strokeLineJoin="round">
{sp_grad}
  </path>
  <path android:pathData="{sp_tris}" android:fillColor="{hexa(ARROW)}"/>
  <path android:pathData="{sp_overA}" android:strokeWidth="{STROKE:.1f}"
    android:strokeLineCap="round" android:strokeLineJoin="round">
{sp_grad}
  </path>
  <path android:pathData="{sp_overB}" android:strokeWidth="{STROKE:.1f}"
    android:strokeLineCap="round" android:strokeLineJoin="round">
{sp_grad}
  </path>
</vector>
'''
open(os.path.join(RES,"drawable","splash_logo.xml"),"w",encoding="utf-8").write(splash)

splash_bg = '''<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:drawable="@android:color/black"/>
    <item android:width="240dp" android:height="240dp" android:gravity="center"
        android:drawable="@drawable/splash_logo"/>
</layer-list>
'''
open(os.path.join(RES,"drawable","splash_background.xml"),"w",encoding="utf-8").write(splash_bg)

# Il vecchio nine-patch ha lo stesso nome risorsa del nuovo vettore: va rimosso.
old9 = os.path.join(RES,"drawable","splash_logo.9.png")
if os.path.exists(old9): os.remove(old9)

# ============ 4) PNG legacy (API 23-25) via Pillow ============
def hex2rgb(c):
    c=c.lstrip("#"); return tuple(int(c[i:i+2],16) for i in (0,2,4))
c_bg=hex2rgb(BG); c_arrow=hex2rgb(ARROW); c0=np.array(hex2rgb(TP0)); c1=np.array(hex2rgb(TP1))

R = 1024; sc = R/320.0
def S(pts): return [(x*sc,y*sc) for x,y in pts]

# gradiente full-canvas (numpy)
yy,xx = np.mgrid[0:R,0:R].astype(np.float64)
tx,ty = xx/sc, yy/sc
ax,ay = G1[0]-G0[0], G1[1]-G0[1]; den=ax*ax+ay*ay
t = ((tx-G0[0])*ax+(ty-G0[1])*ay)/den
t = np.clip(t,0,1)[...,None]
grad_rgb = (c0*(1-t)+c1*t).astype(np.uint8)
grad_img = Image.fromarray(np.dstack([grad_rgb, np.full((R,R),255,np.uint8)]),"RGBA")

def stroke_mask(dense_list):
    m=Image.new("L",(R,R),0); d=ImageDraw.Draw(m)
    w=int(round(STROKE*sc))
    for dense in dense_list:
        pts=S(dense)
        d.line(pts,fill=255,width=w,joint="curve")
        rcap=w/2.0
        for ep in (pts[0],pts[-1]):
            d.ellipse([ep[0]-rcap,ep[1]-rcap,ep[0]+rcap,ep[1]+rcap],fill=255)
    return m

def render(shape):
    icon=Image.new("RGBA",(R,R),(0,0,0,0)); d=ImageDraw.Draw(icon)
    shape_mask=Image.new("L",(R,R),0); ds=ImageDraw.Draw(shape_mask)
    if shape=="round":
        ds.ellipse([0,0,R-1,R-1],fill=255)
    else:
        ds.rounded_rectangle([0,0,R-1,R-1],radius=60*sc,fill=255)
    # bg
    bgimg=Image.new("RGBA",(R,R),c_bg+(255,))
    icon=Image.composite(bgimg,icon,shape_mask)
    # toolpath UNDER
    icon=Image.composite(grad_img,icon,stroke_mask([main_dense]))
    # frecce
    d=ImageDraw.Draw(icon)
    for tri in ARROWS: d.polygon(S(tri),fill=c_arrow+(255,))
    # toolpath OVER (intreccio)
    icon=Image.composite(grad_img,icon,stroke_mask([overA_dense,overB_dense]))
    # clip alla forma
    r,g,b,a=icon.split()
    a=Image.composite(a,Image.new("L",(R,R),0),shape_mask)
    icon=Image.merge("RGBA",(r,g,b,a))
    return icon

icon_sq=render("square"); icon_rd=render("round")
DENS={"mdpi":48,"hdpi":72,"xhdpi":96,"xxhdpi":144,"xxxhdpi":192}
for name,size in DENS.items():
    folder=os.path.join(RES,f"mipmap-{name}"); os.makedirs(folder,exist_ok=True)
    icon_sq.resize((size,size),Image.LANCZOS).save(os.path.join(folder,"ic_launcher.png"))
    icon_rd.resize((size,size),Image.LANCZOS).save(os.path.join(folder,"ic_launcher_round.png"))

print("OK: SVG, VectorDrawable, adaptive-icon e PNG legacy generati.")
print("design/ic_launcher.svg")
print("drawable/ic_launcher_foreground.xml, ic_launcher_background.xml")
print("mipmap-anydpi-v26/ic_launcher.xml (+round)")
print("mipmap-*/ic_launcher.png (+round) per", list(DENS.keys()))
