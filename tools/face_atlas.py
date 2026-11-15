# Атлас лиц: 6 выражений в ряд (512x256 каждое). Запуск: python3 face_atlas.py girl|boy out.png
import sys, subprocess
tag,out=sys.argv[1],sys.argv[2]
G=tag=='girl'
IR=('#6B2A12','#E8781E','#FFC23D','#FFE27A') if G else ('#0E3A40','#1F9AA0','#5FE0D0','#B8FFF0')
LASH='#2B1A3A' if G else '#1B2238'; MOUTH='#B8485E' if G else '#8A3E44'
EX=[123,389]; EY=64; MY=182      # центры глаз и рта в ячейке 512x256
def eye(cx,mode,flip):
    s=-1 if flip else 1
    if mode=='open' or mode=='wide' or mode=='sad':
        ry=44 if mode!='wide' else 50; rx=38
        top=f'M {cx-44*1},{EY-10} C {cx-36},{EY-52} {cx+36},{EY-52} {cx+46},{EY-14} C {cx+30},{EY-36} {cx-28},{EY-38} {cx-44},{EY-10} Z'
        lash='' if not G else f'<path d="M {cx-s*44},{EY-12} l {-s*14},{-10} l {s*16},{-4} Z" fill="{LASH}"/>'
        lid='' if mode!='sad' else f'<rect x="{cx-50}" y="{EY-60}" width="100" height="34" fill="#FFE3D3"/><path d="M {cx-44},{EY-22} L {cx+44},{EY-22}" stroke="{LASH}" stroke-width="7" stroke-linecap="round"/>'
        return f'''<ellipse cx="{cx}" cy="{EY}" rx="{rx+6}" ry="{ry}" fill="#FFFFFF"/>
<clipPath id="c{cx}{mode}"><ellipse cx="{cx}" cy="{EY}" rx="{rx+6}" ry="{ry}"/></clipPath>
<g clip-path="url(#c{cx}{mode})"><ellipse cx="{cx}" cy="{EY+2}" rx="{rx-6}" ry="{ry-2}" fill="url(#iris)"/>
<ellipse cx="{cx}" cy="{EY+2}" rx="{rx-6}" ry="{ry-2}" fill="none" stroke="#1E1A3A" stroke-width="4" opacity="0.6"/>
<ellipse cx="{cx}" cy="{EY}" rx="11" ry="15" fill="#1A1028"/><ellipse cx="{cx}" cy="{EY-34}" rx="44" ry="20" fill="#1A1028" opacity="0.45"/></g>
{'' if mode=='sad' else f'<path d="{top}" fill="{LASH}"/>'}{lash if mode!='sad' else ''}{lid}
<ellipse cx="{cx-14}" cy="{EY-16}" rx="10" ry="11" fill="#FFFFFF"/><circle cx="{cx+14}" cy="{EY+20}" r="5" fill="#FFFFFF" opacity="0.9"/>'''
    if mode=='closed':   # спит / моргает: дуга вниз
        return f'<path d="M {cx-40},{EY+4} Q {cx},{EY+26} {cx+40},{EY+4}" fill="none" stroke="{LASH}" stroke-width="8" stroke-linecap="round"/>'
    if mode=='happy':    # улыбка глазами: дуга вверх
        return f'<path d="M {cx-40},{EY+14} Q {cx},{EY-26} {cx+40},{EY+14}" fill="none" stroke="{LASH}" stroke-width="9" stroke-linecap="round"/>'
def mouth(mode):
    if mode=='smile': return f'<path d="M 232,{MY-4} Q 256,{MY+12} 280,{MY-4}" fill="none" stroke="{MOUTH}" stroke-width="6" stroke-linecap="round"/>'
    if mode=='open':  return f'<path d="M 230,{MY-10} Q 256,{MY-4} 282,{MY-10} Q 276,{MY+26} 256,{MY+26} Q 236,{MY+26} 230,{MY-10} Z" fill="#A8344A" stroke="#6E2030" stroke-width="5" stroke-linejoin="round"/><ellipse cx="256" cy="{MY+16}" rx="13" ry="7" fill="#F08A9A"/>'
    if mode=='sad':   return f'<path d="M 236,{MY+8} Q 256,{MY-8} 276,{MY+8}" fill="none" stroke="{MOUTH}" stroke-width="6" stroke-linecap="round"/>'
    if mode=='o':     return f'<ellipse cx="256" cy="{MY+4}" rx="10" ry="13" fill="#A8344A" stroke="#6E2030" stroke-width="4"/>'
    if mode=='line':  return f'<path d="M 240,{MY+2} L 272,{MY+2}" stroke="{MOUTH}" stroke-width="6" stroke-linecap="round"/>'
CELLS=[('open','smile',0.30),('closed','line',0.30),('happy','open',0.55),('sad','sad',0.10),('open','open',0.30),('wide','o',0.30)]
body=''
for i,(e,m,bl) in enumerate(CELLS):
    blush=f'<ellipse cx="96" cy="140" rx="46" ry="20" fill="#FF7C96" opacity="{bl}" filter="url(#b)"/><ellipse cx="416" cy="140" rx="46" ry="20" fill="#FF7C96" opacity="{bl}" filter="url(#b)"/>'
    body+=f'<g transform="translate({i*512},0)">{blush}{eye(EX[0],e,False)}{eye(EX[1],e,True)}{mouth(m)}</g>'
svg=f'''<svg xmlns="http://www.w3.org/2000/svg" width="3072" height="256" viewBox="0 0 3072 256"><defs>
<linearGradient id="iris" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{IR[0]}"/><stop offset="0.45" stop-color="{IR[1]}"/><stop offset="0.8" stop-color="{IR[2]}"/><stop offset="1" stop-color="{IR[3]}"/></linearGradient>
<filter id="b" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="9"/></filter></defs>{body}</svg>'''
open(f'face_{tag}.svg','w').write(svg)
subprocess.run(['rsvg-convert','-w','2048','-h','256','--keep-aspect-ratio' ,f'face_{tag}.svg','-o',out],check=False)
subprocess.run(['rsvg-convert','-w','2048','-h','256',f'face_{tag}.svg','-o',out],check=True)
