import numpy as np, sys
from PIL import Image
from load import *
def hexc(h): return np.array([int(h[i:i+2],16) for i in (1,3,5)],np.float32)/255
def sstep(a,b,x): t=np.clip((x-a)/(b-a),0,1); return t*t*(3-2*t)
def vnormals(V,F):
    t=V[F]; n=np.cross(t[:,1]-t[:,0],t[:,2]-t[:,0]); N=np.zeros_like(V)
    for i in range(3): np.add.at(N,F[:,i],n)
    return N/(np.linalg.norm(N,axis=1,keepdims=True)+1e-12)

def refine_face(face,x,y,z):
    # убираем из маски лица пряди: всё, что выступает над гладкой поверхностью лица
    idx=np.where(face)[0]; keep=np.ones(len(idx),bool)
    for _ in range(4):
        A=np.c_[np.ones(keep.sum()),x[idx][keep],y[idx][keep],x[idx][keep]**2,y[idx][keep]**2,x[idx][keep]*y[idx][keep]]
        co,*_=np.linalg.lstsq(A,z[idx][keep],rcond=None)
        Af=np.c_[np.ones(len(idx)),x[idx],y[idx],x[idx]**2,y[idx]**2,x[idx]*y[idx]]
        r=z[idx]-Af@co; keep=r<0.0035
    out=np.zeros_like(face); out[idx[keep]]=True; return out

def paint_girl(V,N):
    x,y,z=V.T; ax=np.abs(x); C=np.zeros((len(V),3),np.float32)
    SKIN=hexc('#FFE3D3'); HAIR=hexc('#7B5BD6'); HAIR2=hexc('#B79CFF'); JACKET=hexc('#23233A'); SKIRT=hexc('#2E2E4C'); SKIRT2=hexc('#454570')
    SHIRT=hexc('#FAFAFF'); SHOE=hexc('#3A2A2A'); SOCK=hexc('#FFFFFF'); BOW=hexc('#E8366B')
    C[:]=JACKET
    # ноги, носки, туфли
    leg=y<0.52; C[leg]=SKIN; C[leg&(y<0.125)]=SOCK; C[y<0.05]=SHOE
    # юбка со складками
    sk=(y>=0.505)&(y<0.635)&(ax<0.14); ang=np.arctan2(z,x); pl=0.5+0.5*np.sin(ang*14)
    C[sk]=SKIRT+(SKIRT2-SKIRT)*sstep(0.55,0.9,pl[sk])[:,None]
    # ноги под юбкой остаются кожей
    inner=(y>0.49)&(y<0.56)&(np.hypot(np.minimum(np.abs(x-0.045),np.abs(x+0.045)),z)<0.036); C[inner&(y<0.535)]=SKIN
    # кисти
    hand=(ax>0.085)&(y<0.537)&(y>0.44); C[hand]=SKIN
    # рубашка в вырезе и бант
    vee=(z>0.02)&(y>0.70)&(y<0.815)&(ax<0.012+(y-0.70)*0.42); C[vee]=SHIRT
    bow=(z>0.03)&(np.abs(y-0.768)<0.012)&(ax<0.028)&~((ax<0.02)&(ax>0.008)&(np.abs(y-0.768)<0.004)); C[bow&vee]=BOW
    # шея и лицо
    neck=(y>0.80)&(y<0.862)&(ax<0.036)&(z>-0.03); neck&=~((N[:,2]<0.75)&(z>0.045)&(y>0.82)); C[neck]=SKIN
    face=(y>0.850)&(y<FACE_TOP)&(ax<0.052)&(z>0.03); C[face]=SKIN
    # волосы
    head=(y>0.855)&~face
    back=(z<-0.03)&(y>0.705)&(ax<0.088)&~((ax>0.065)&(y<0.80))
    side=(y>0.80)&(ax>0.036)&~face&(ax<0.125)&~((y<0.83)&(ax>0.075))
    hair=head|back|side
    g=sstep(0.70,1.0,y)[:,None]; band=np.exp(-((y-0.955)/0.012)**2)[:,None]*(N[:,1:2]>0.2)
    C[hair]=(HAIR*(0.80+0.25*g)+ (HAIR2-HAIR)*0.55*band)[hair]
    # лицо: румянец, глаза, рот
    for sx in (-1,1):
        d=np.hypot((x-sx*0.036)/0.012,(y-0.877)/0.007); C[face]=(C+(hexc('#FF8FA3')-C)*0.45*np.exp(-d*d)[:,None])[face]
        e=np.hypot((x-sx*EYE_X)/EYE_RX,(y-EYE_Y)/EYE_RY)
        C[face&(e<1.0)]=hexc('#2E1B3F')
        C[face&(np.hypot((x-sx*EYE_X+0.004)/0.003,(y-EYE_Y-0.003)/0.003)<1)]=1.0
    m=face&(np.abs(y-0.8675+ (x*9)**2)<0.0018)&(ax<0.008); C[m]=hexc('#C2566B')
    w=np.maximum(sstep(0.815,0.85,y), hair*sstep(0.69,0.80,y))
    return C,w
FACE_TOP=0.905; EYE_X=0.026; EYE_RX=0.0105; EYE_RY=0.0085; EYE_Y=0.892

def paint_boy(V,N):
    x,y,z=V.T; ax=np.abs(x); C=np.zeros((len(V),3),np.float32)
    SKIN=hexc('#FBDCC8'); HAIR=hexc('#2F3F78'); HAIR2=hexc('#7FA8E8'); JACKET=hexc('#2B2F45'); TEE=hexc('#F4F5FA'); JEANS=hexc('#44558C'); SHOE=hexc('#FAFAFF'); TEAL=hexc('#2FC2B8')
    C[:]=JACKET
    C[y<0.555]=JEANS; C[(ax>0.15)&(y>0.5)]=JACKET; C[(y<0.515)&(np.abs(np.sin(y*260))>0.985)&(y<0.2)]=JEANS*0.85
    C[y<0.062]=SHOE; C[y<0.012]=TEAL
    tee=(z>0.0)&(y>0.50)&(y<0.80)&(ax<0.060-(y-0.52)*0.07); C[tee]=TEE; C[tee&(y<0.515)]=TEE
    C[(y>0.5)&(y<0.56)&(ax<0.12)&(C[:,0]<0.3)&(z>0)&(ax<0.075)]=TEE
    logo=tee&(np.abs(np.hypot(x,y-0.70)-0.022)<0.004); C[logo]=TEAL
    neck=(y>0.805)&(y<0.87)&(ax<np.where(y<0.835,0.028,0.040))&(z>-0.035); C[neck]=SKIN
    face=(y>0.852)&(y<BFACE_TOP)&(ax<0.050)&(z>0.02); C[face]=SKIN
    hair=(y>0.868)&~face
    g=sstep(0.86,1.0,y)[:,None]; band=np.exp(-((y-0.95)/0.012)**2)[:,None]*(N[:,1:2]>0.2)
    C[hair]=(HAIR*(0.85+0.3*g)+(HAIR2-HAIR)*0.5*band)[hair]
    for sx in (-1,1):
        e=np.hypot((x-sx*0.024)/0.0095,(y-BEYE_Y)/0.0075)
        C[face&(e<1.0)]=hexc('#16324A')
        C[face&(np.hypot((x-sx*0.024+0.004)/0.0028,(y-BEYE_Y-0.002)/0.0028)<1)]=1.0
    m=face&(np.abs(y-0.871)<0.0016)&(ax<0.007); C[m]=hexc('#A8505A')
    w=np.maximum(sstep(0.82,0.86,y), 0)
    return C,w
BFACE_TOP=0.912; BEYE_Y=0.900

if __name__=='__main__':
    tag=sys.argv[1]; d=np.load(tag+'_n.npz'); V,F=d['V'],d['F']; N=vnormals(V,F)
    C,w=(paint_girl if tag=='girl' else paint_boy)(V,N)
    np.savez(tag+'_c.npz',V=V,F=F,N=N,C=C,w=w)
    ims=[Image.fromarray(render(V,F,C,yaw=a,S=900)) for a in (0,2.2,np.pi)]
    m=(V[F].min(1)[:,1]>0.78); ims.append(Image.fromarray(render(V,F[m],C,yaw=0,S=900,dense=9)))
    W=sum(i.width for i in ims); out=Image.new('RGB',(W,900),(110,110,110)); xx=0
    for i in ims: out.paste(i,(xx,0)); xx+=i.width
    out.save(tag+'_paint.png'); print(out.size)
