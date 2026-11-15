import numpy as np
from scipy.sparse import coo_matrix
from scipy.sparse.csgraph import connected_components
def face_adj(F):
    nf=len(F); e=np.r_[F[:,[0,1]],F[:,[1,2]],F[:,[2,0]]]; fid=np.r_[np.arange(nf)]*1; fid=np.tile(np.arange(nf),3)
    key=np.sort(e,axis=1); k=key[:,0].astype(np.int64)*10**7+key[:,1]; o=np.argsort(k); k=k[o]; fid=fid[o]
    same=k[1:]==k[:-1]; return fid[:-1][same],fid[1:][same]
def segment(V,F,thr_deg=30,concave_only=True):
    t=V[F]; n=np.cross(t[:,1]-t[:,0],t[:,2]-t[:,0]); n/=np.linalg.norm(n,axis=1,keepdims=True)+1e-12; c=t.mean(1)
    a,b=face_adj(F); cosang=np.clip((n[a]*n[b]).sum(1),-1,1); ang=np.degrees(np.arccos(cosang))
    conc=((c[b]-c[a])*n[a]).sum(1)>0
    cut=(ang>thr_deg)&(conc if concave_only else True)
    keep=~cut; nf=len(F)
    A=coo_matrix((np.ones(keep.sum()),(a[keep],b[keep])),shape=(nf,nf))
    nc,lab=connected_components(A,directed=False); return lab,nc,n,c
if __name__=='__main__':
    import sys
    from PIL import Image
    from load import render
    tag=sys.argv[1]; thr=float(sys.argv[2])
    d=np.load(tag+'_n.npz'); V,F=d['V'],d['F']
    lab,nc,n,c=segment(V,F,thr); sizes=np.bincount(lab); print(tag,'regions',nc,'top',np.sort(sizes)[::-1][:25])
    rng=np.random.RandomState(1); pal=rng.rand(nc,3)*0.8+0.2; pal[sizes<30]=0.15
    # цвет по граням -> раздуваем вершины
    V2=V[F].reshape(-1,3); F2=np.arange(len(V2)).reshape(-1,3); C2=np.repeat(pal[lab],3,axis=0)
    ims=[Image.fromarray(render(V2,F2,C2,yaw=a,S=800,dense=3)) for a in (0,1.2,np.pi)]
    W=sum(i.width for i in ims); o=Image.new('RGB',(W,800)); x=0
    for i in ims: o.paste(i,(x,0)); x+=i.width
    o.save(tag+'_seg.png')
