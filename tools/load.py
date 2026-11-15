import numpy as np, sys
def load(path):
    vs=[];fs=[]
    with open(path) as f:
        for l in f:
            if l.startswith('v '): vs.append(l.split()[1:4])
            elif l.startswith('f '): fs.append([p.split('/')[0] for p in l.split()[1:4]])
    return np.array(vs,dtype=np.float32), np.array(fs,dtype=np.int32)-1
def render(V,F,C=None,yaw=0,S=700,light=(0.4,0.5,0.8),dense=0):
    c,s=np.cos(yaw),np.sin(yaw)
    R=np.array([[c,0,s],[0,1,0],[-s,0,c]],dtype=np.float32)
    P=V@R.T
    U=np.unique(F); mn,mx=P[U].min(0),P[U].max(0); sc=(S-20)/max(mx[1]-mn[1],mx[0]-mn[0])
    W=int((mx[0]-mn[0])*sc)+20
    x=(P[:,0]-mn[0])*sc+10; y=(mx[1]-P[:,1])*sc+10; z=P[:,2]
    tri=P[F]; n=np.cross(tri[:,1]-tri[:,0],tri[:,2]-tri[:,0]); n/=np.linalg.norm(n,axis=1,keepdims=True)+1e-9
    L=np.array(light);L/=np.linalg.norm(L); sh=np.clip(n@L,0,1)*0.7+0.3
    img=np.full((S,W,3),0.45,np.float32); zb=np.full((S,W),-1e9,np.float32)
    # splat samples per triangle
    pts=[(1/3,1/3,1/3),(0.6,0.2,0.2),(0.2,0.6,0.2),(0.2,0.2,0.6),(1,0,0),(0,1,0),(0,0,1),(.5,.5,0),(0,.5,.5),(.5,0,.5)]
    if dense: pts=[(i/dense,j/dense,1-i/dense-j/dense) for i in range(dense+1) for j in range(dense+1-i)]
    for (a,b,cc) in pts:
        px=(a*x[F[:,0]]+b*x[F[:,1]]+cc*x[F[:,2]]).astype(int); py=(a*y[F[:,0]]+b*y[F[:,1]]+cc*y[F[:,2]]).astype(int)
        pz=a*z[F[:,0]]+b*z[F[:,1]]+cc*z[F[:,2]]
        col = sh[:,None]*(np.ones(3) if C is None else (a*C[F[:,0]]+b*C[F[:,1]]+cc*C[F[:,2]]))
        o=np.argsort(pz); px,py,pz,col=px[o],py[o],pz[o],col[o]
        for dx in (0,1):
            for dy in (0,1):
                xx=np.clip(px+dx,0,W-1); yy=np.clip(py+dy,0,S-1)
                m=pz>=zb[yy,xx]; img[yy[m],xx[m]]=col[m]; zb[yy[m],xx[m]]=pz[m]
    return (np.clip(img,0,1)*255).astype(np.uint8)
