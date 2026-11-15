import numpy as np, sys
from PIL import Image
from load import render
from seg2 import *
SKIN,HAIR,JACKET,SHIRT,SKIRT,SHOE,PANTS,TEE=range(8)
DBG=[hexc(c) for c in ('#FFD9C4','#7B5BD6','#22223A','#FFFFFF','#E0457B','#5A3A2A','#44558C','#9FE0D0')]

def seeds_girl(V,N,cv):
    x,y,z=V.T; ax=np.abs(x); flat=np.abs(cv)<6
    s={}
    s[SHOE]=(y<0.028)
    s[SKIN]=((y>0.10)&(y<0.47)&(ax<0.085)) | ((ax>0.088)&(y>0.455)&(y<0.515)) \
          | ((ax<0.018)&(z>0.0)&(y>0.826)&(y<0.846)&(N[:,2]>0.6)) \
          | ((ax<0.034)&(y>0.860)&(y<0.898)&(z>0.045)&(N[:,2]>0.75)&flat)
    s[SKIRT]=(y>0.555)&(y<0.612)&(ax<0.085)
    s[JACKET]=((y>0.645)&(y<0.688)&(ax<0.085)) | ((ax>0.102)&(y>0.60)&(y<0.77)) | ((y>0.70)&(y<0.78)&(ax>0.06)&(ax<0.09)&(z>0.0)) | ((ax>0.055)&(ax<0.09)&(y>0.795)&(y<0.812)&(z>-0.02))
    s[SHIRT]=((z>0.03)&(ax<0.007)&(y>0.715)&(y<0.775)) | ((z>0.02)&(ax<0.03)&(y>0.790)&(y<0.806))
    s[HAIR]=(y>0.935) | ((z<-0.06)&(y>0.74)&(ax<0.06)) | ((ax>0.085)&(y>0.86)) | ((y>0.915)&(z>0.03))
    return s
def seeds_boy(V,N,cv):
    x,y,z=V.T; ax=np.abs(x); flat=np.abs(cv)<6
    s={}
    s[SHOE]=(y<0.03)
    s[PANTS]=(y>0.10)&(y<0.47)
    s[JACKET]=((ax>0.09)&(y>0.60)&(y<0.78)) | ((z<-0.03)&(y>0.58)&(y<0.78)) | ((ax>0.07)&(y>0.785)&(y<0.82))
    s[TEE]=((z>0.03)&(ax<0.02)&(y>0.56)&(y<0.74))
    s[SKIN]=((ax<0.016)&(z>0.0)&(y>0.825)&(y<0.845)&(N[:,2]>0.3)) | ((ax<0.03)&(y>0.857)&(y<0.884)&(z>0.0)&(N[:,2]>0.6)&(np.abs(cv)<10))
    print('boy skin seeds',s[SKIN].sum(), 'face z', z[(ax<0.03)&(y>0.858)&(y<0.89)&(N[:,2]>0.6)].min() if ((ax<0.03)&(y>0.858)&(y<0.89)&(N[:,2]>0.6)).any() else None)
    s[HAIR]=(y>0.93) | ((z<-0.05)&(y>0.89)) | ((ax>0.075)&(y>0.89))
    return s

if __name__=='__main__':
    tag=sys.argv[1]; d=np.load(tag+'_n.npz'); V,F=d['V'],d['F']; N=vnormals(V,F)
    G,cv,ed=edge_graph(V,F)
    lab=grow(G,(seeds_girl if tag=='girl' else seeds_boy)(V,N,cv))
    x,y,z=V.T
    if tag=='girl': lab[(lab==HAIR)&(y<0.83)&(np.abs(x)<0.03)&(z>0)]=SHIRT
    else: lab[(lab==HAIR)&(y<0.845)&(z>-0.03)]=TEE
    # сглаживаем границы: вершина берёт метку большинства соседей
    from scipy.sparse import csr_matrix
    A=csr_matrix((np.ones(len(ed)*2),(np.r_[ed[:,0],ed[:,1]],np.r_[ed[:,1],ed[:,0]])),shape=(len(V),len(V)))
    for _ in range(8):
        H=np.stack([(lab==k).astype(np.float32) for k in range(8)],1); H=H+A@H; lab=H.argmax(1)
    np.savez(tag+'_lab.npz',lab=lab,cv=cv)
    C=np.array(DBG)[lab]
    ims=[Image.fromarray(render(V,F,C,yaw=a,S=800,dense=3)) for a in (0,1.2,np.pi)]
    m=(V[F].min(1)[:,1]>0.78); ims.append(Image.fromarray(render(V,F[m],C,yaw=0,S=800,dense=8))); ims.append(Image.fromarray(render(V,F[m],C,yaw=1.0,S=800,dense=8)))
    W=sum(i.width for i in ims); o=Image.new('RGB',(W,800)); xx=0
    for i in ims: o.paste(i,(xx,0)); xx+=i.width
    o=o.resize((o.width*3//4,600)); o.save(tag+'_lab.png'); print(o.size, np.bincount(lab))
