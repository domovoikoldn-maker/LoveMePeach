# Полная сборка модели: метки частей -> цвета, лицо, скелет и веса -> .mesh (формат v2) + превью
import numpy as np, sys, struct, os
from PIL import Image
from scipy.sparse import csr_matrix
from seg2 import edge_graph, grow
from paint import vnormals, hexc, sstep
SKIN,HAIR,JACKET,SHIRT,SKIRT,SHOE,PANTS,TEE=range(8)
HIPS,SPINE,CHEST,HEAD,ARM_LU,ARM_LL,ARM_RU,ARM_RL,TH_L,SH_L,TH_R,SH_R=range(12)
PARENT=[-1,0,1,2,2,4,2,6,0,8,0,10]

def adjacency(ed,n): return csr_matrix((np.ones(len(ed)*2),(np.r_[ed[:,0],ed[:,1]],np.r_[ed[:,1],ed[:,0]])),shape=(n,n))
def smooth(A,f,it):
    deg=np.asarray(A.sum(1)).ravel()+1
    for _ in range(it): f=(f+A@f)/(deg[:,None] if f.ndim==2 else deg)
    return f

def colors(tag,V,N,lab,cv,A):
    x,y,z=V.T; ax=np.abs(x); C=np.zeros((len(V),3),np.float32)
    ao=np.clip(smooth(A,cv,3)/22,-1,1); ang=np.arctan2(z,x)
    if tag=='girl':
        base={SKIN:'#FFE4D6',HAIR:'#8466E2',JACKET:'#2A2C50',SHIRT:'#FFFFFF',SKIRT:'#3A3D6B',SHOE:'#4A2F2A'}
    else:
        base={SKIN:'#FCDCCB',HAIR:'#33488A',JACKET:'#2C3048',TEE:'#F6F7FC',PANTS:'#4A5E9C',SHOE:'#FAFAFF'}
    for k,h in base.items(): C[lab==k]=hexc(h)
    hair=lab==HAIR
    g=sstep(0.68,1.0,y); band=np.exp(-((y-0.953)/0.011)**2)*(N[:,1]>0.15)
    lite=hexc('#C9B6FF') if tag=='girl' else hexc('#8FB4F0')
    C[hair]=(C*(0.82+0.24*g[:,None])+(lite-C)*0.55*band[:,None])[hair]
    if tag=='girl':
        tips=sstep(0.80,0.70,y)[:,None]; C[hair]=(C+(hexc('#F28AC8')-C)*0.45*tips)[hair]          # розовые кончики
        sk=lab==SKIRT; pl=0.5+0.5*np.sin(ang*16); C[sk]=(C+(hexc('#5A5E96')-C)*sstep(0.6,0.95,pl)[:,None])[sk]
        hem=sk&(y<V[sk,1].min()+0.012); C[hem]=hexc('#E8366B')
        C[(lab==SKIN)&(y<0.128)&(ax<0.09)]=1.0                                                    # носки
        C[(lab==SKIN)&(y<0.128)&(y>0.118)&(ax<0.09)]=hexc('#E8366B')
        bow=(lab==SHIRT)&(np.abs(y-0.772)<0.011)&(ax<0.026); C[bow]=hexc('#E8366B')
        C[(lab==JACKET)&(ax>0.085)&(y<V[(lab==JACKET)&(ax>0.085),1].min()+0.012)]=hexc('#E8366B')  # манжеты
    else:
        C[(lab==SHOE)&(y<0.012)]=hexc('#2FC2B8')
        logo=(lab==TEE)&(z>0)&(np.abs(np.hypot(x,y-0.70)-0.020)<0.0035); C[logo]=hexc('#2FC2B8')
        pk=lab==PANTS; C[pk]=(C*(1+0.10*np.sin(y*90)[:,None]*0))[pk]
    # запечённые тени в складках и светлые кромки — «рисованный» объём
    k=np.where(lab==SKIN,0.10,0.30)
    C*= (1-k*np.maximum(ao,0)+0.10*np.maximum(-ao,0))[:,None]
    C[lab==SKIN]+= (np.array([0.02,-0.01,-0.01])*np.maximum(ao,0)[:,None])[lab==SKIN]
    return np.clip(C,0,1)

def rig(tag,V,lab,G,A,ed):
    x,y,z=V.T; ax=np.abs(x); n=len(V); b=np.zeros(n,int)
    knee=0.29 if tag=='girl' else 0.285
    b[:]=np.select([y<0.60,y<0.70,y<0.825],[HIPS,SPINE,CHEST],HEAD)
    b[(lab==HAIR)&(y<0.80)]=CHEST; b[(lab==HAIR)&(y>=0.80)]=HEAD
    legs=(y<0.50)&(lab!=SKIRT)&((ax<0.085) if tag=='girl' else True)
    b[legs&(x>0)]=np.where(y[legs&(x>0)]>knee,TH_L,SH_L); b[legs&(x<=0)]=np.where(y[legs&(x<=0)]>knee,TH_R,SH_R)
    J=np.zeros((12,3),np.float32)
    def cen(m): return V[m].mean(0)
    if tag=='girl':
        part=grow(G,{1:(ax>0.097)&(y>0.52)&(y<0.775), 0:((ax<0.05)&(y>0.56)&(y<0.80))|(y<0.42)|((ax<0.07)&(y<0.56))})
        arm=(part==1)&(y<0.80)&(y>0.43)&(ax>0.07)
        elbow=0.665
        b[arm&(x>0)]=np.where(y[arm&(x>0)]>elbow,ARM_LU,ARM_LL); b[arm&(x<0)]=np.where(y[arm&(x<0)]>elbow,ARM_RU,ARM_RL)
        el=cen(arm&(x>0)&(np.abs(y-elbow)<0.01))
        print('arm verts',arm.sum(),'ymax',y[arm].max()); J[ARM_LU]=[el[0]-0.008,0.785,el[2]]; J[ARM_LL]=[el[0],elbow,el[2]]
    else:
        J[ARM_LU]=[0.11,0.785,0]; J[ARM_LL]=[0.14,0.66,0]
    J[ARM_RU]=J[ARM_LU]*[-1,1,1]; J[ARM_RL]=J[ARM_LL]*[-1,1,1]
    hip=cen(legs&(x>0)&(y>0.47)); kn=cen(legs&(x>0)&(np.abs(y-knee)<0.01))
    J[HIPS]=[0,0.55,0]; J[SPINE]=[0,0.61,0]; J[CHEST]=[0,0.71,0]; J[HEAD]=[0,0.83,cen((y>0.82)&(y<0.84)&(ax<0.03))[2]]
    J[TH_L]=[min(hip[0],kn[0]*1.15),0.535,hip[2]]; J[SH_L]=[kn[0],knee,kn[2]]; J[TH_R]=J[TH_L]*[-1,1,1]; J[SH_R]=J[SH_L]*[-1,1,1]
    # кисти в модели «приклеены» к бёдрам: при сглаживании весов разрываем эту связь
    isarm=np.isin(b,[ARM_LU,ARM_LL,ARM_RU,ARM_RL]); cutv=y<0.62
    keep=~((isarm[ed[:,0]]!=isarm[ed[:,1]])&cutv[ed[:,0]]&cutv[ed[:,1]])
    A=adjacency(ed[keep],n)
    W=np.zeros((n,12),np.float32); W[np.arange(n),b]=1; W=smooth(A,W,7)
    if tag=='girl':   # юбка частично идёт за бёдрами
        sk=lab==SKIRT; a=0.55*sstep(0.63,0.51,y[sk]); W[sk]=0; idx=np.where(sk)[0]
        W[idx,HIPS]=1-a; W[idx,np.where(x[sk]>0,TH_L,TH_R)]=a
    o=np.argsort(-W,axis=1)[:,:2]; w0=W[np.arange(n),o[:,0]]; w1=W[np.arange(n),o[:,1]]; w0=w0/(w0+w1+1e-9)
    return o.astype(np.uint8), w0, J, isarm

# ---------- скелет: поза -> матрицы костей (то же самое делает Kotlin) ----------
def rot(rx,ry,rz):
    cx,sx,cy,sy,cz,sz=np.cos(rx),np.sin(rx),np.cos(ry),np.sin(ry),np.cos(rz),np.sin(rz)
    Rx=np.array([[1,0,0],[0,cx,-sx],[0,sx,cx]]); Ry=np.array([[cy,0,sy],[0,1,0],[-sy,0,cy]]); Rz=np.array([[cz,-sz,0],[sz,cz,0],[0,0,1]])
    return Rz@Ry@Rx
def bone_mats(J,pose,root=(0,0,0)):
    M=[None]*12
    for i in range(12):
        L=np.eye(4); R=rot(*pose.get(i,(0,0,0))); L[:3,:3]=R; L[:3,3]=J[i]-R@J[i]
        if i==0: L[:3,3]+=root
        M[i]=L if PARENT[i]<0 else M[PARENT[i]]@L
    return np.array(M)
def skin(V,bi,w0,M):
    P=np.c_[V,np.ones(len(V))]; a=np.einsum('nij,nj->ni',M[bi[:,0]],P)[:,:3]; b=np.einsum('nij,nj->ni',M[bi[:,1]],P)[:,:3]
    return (a*w0[:,None]+b*(1-w0)[:,None]).astype(np.float32)

def render(P,F,C,rest,flag,atlas,cell,rect,yaw=0,pitch=0,S=700,dense=4,bounds=None):
    c,s=np.cos(yaw),np.sin(yaw); R=np.array([[c,0,s],[0,1,0],[-s,0,c]],np.float32)
    cp,sp=np.cos(pitch),np.sin(pitch); R=np.array([[1,0,0],[0,cp,-sp],[0,sp,cp]],np.float32)@R
    Q=P@R.T; U=np.unique(F); mn,mx=(Q[U].min(0),Q[U].max(0)) if bounds is None else bounds
    sc=(S-20)/max(mx[1]-mn[1],mx[0]-mn[0]); W=int((mx[0]-mn[0])*sc)+20
    X=(Q[:,0]-mn[0])*sc+10; Y=(mx[1]-Q[:,1])*sc+10; Z=Q[:,2]
    t=Q[F]; n=np.cross(t[:,1]-t[:,0],t[:,2]-t[:,0]); n/=np.linalg.norm(n,axis=1,keepdims=True)+1e-9
    L=np.array([0.35,0.6,0.75]); L/=np.linalg.norm(L); lit=np.where(n@L>0.0,1.0,0.0)[:,None]; shade=lit+(1-lit)*np.array([0.66,0.62,0.78])
    img=np.full((S,W,3),0.45,np.float32); zb=np.full((S,W),-1e9,np.float32)
    at=np.asarray(atlas).astype(np.float32)/255; ah,aw=at.shape[:2]
    for i in range(dense+1):
        for j in range(dense+1-i):
            a,b=i/dense,j/dense; cc=1-a-b
            px=(a*X[F[:,0]]+b*X[F[:,1]]+cc*X[F[:,2]]).astype(int); py=(a*Y[F[:,0]]+b*Y[F[:,1]]+cc*Y[F[:,2]]).astype(int); pz=a*Z[F[:,0]]+b*Z[F[:,1]]+cc*Z[F[:,2]]
            col=a*C[F[:,0]]+b*C[F[:,1]]+cc*C[F[:,2]]; rp=a*rest[F[:,0]]+b*rest[F[:,1]]+cc*rest[F[:,2]]; fl=a*flag[F[:,0]]+b*flag[F[:,1]]+cc*flag[F[:,2]]
            u=(rp[:,0]-rect[0])/rect[2]+0.5; v=(rect[1]-rp[:,1])/rect[3]+0.5
            ok=(fl>0.5)&(u>0)&(u<1)&(v>0)&(v<1)
            tx=np.clip(((np.clip(u,0,1)+cell)/6*aw).astype(int),0,aw-1); ty=np.clip((np.clip(v,0,1)*ah).astype(int),0,ah-1); d=at[ty,tx]
            al=(d[:,3]*ok)[:,None]; col=col*(1-al)+d[:,:3]*al
            col=col*shade
            m=(px>=0)&(px<W-1)&(py>=0)&(py<S-1)
            o=np.argsort(pz[m]); qx,qy,qz,qc=px[m][o],py[m][o],pz[m][o],col[m][o]
            for dx in (0,1):
                for dy in (0,1):
                    k=qz>=zb[qy+dy,qx+dx]; img[qy[k]+dy,qx[k]+dx]=qc[k]; zb[qy[k]+dy,qx[k]+dx]=qz[k]
    return (np.clip(img,0,1)*255).astype(np.uint8), zb>-1e8

if __name__=='__main__':
    tag=sys.argv[1]; d=np.load(tag+'_n.npz'); V,F=d['V'],d['F']; N=vnormals(V,F)
    L=np.load(tag+'_lab.npz'); lab,cv=L['lab'],L['cv']
    G,_,ed=edge_graph(V,F); A=adjacency(ed,len(V))
    C=colors(tag,V,N,lab,cv,A); bi,w0,J,isarm=rig(tag,V,lab,G,A,ed)
    fa=isarm[F]; bad=(fa.any(1)&~fa.all(1))&(V[F][:,:,1].max(1)<0.62); print('cut faces',bad.sum()); F=F[~bad]
    x,y,z=V.T; rect=(0.0,0.871,0.088,0.044) if tag=='girl' else (0.0,0.8745,0.088,0.044)
    flag=((lab==SKIN)&(y>0.848)&(N[:,2]>0.1)&(np.abs(x)<0.06)).astype(np.float32)
    np.savez(tag+'_final.npz',V=V,F=F,N=N,C=C,bi=bi,w0=w0,J=J,flag=flag,rect=np.array(rect))
    print(tag,'joints'); print(np.round(J,3))
    A2='/home/claude/lovemepeach/app/src/main'
    vb=np.zeros(len(V),dtype=[('p','<f4',3),('n','i1',3),('f','u1'),('c','u1',4),('b','u1',4)])
    vb['p']=V; vb['n']=np.clip(np.round(N*127),-127,127); vb['f']=(flag*127).astype(np.uint8)
    vb['c'][:,:3]=np.clip(C*255+.5,0,255); vb['c'][:,3]=255; vb['b'][:,0]=bi[:,0]; vb['b'][:,1]=bi[:,1]; vb['b'][:,2]=np.clip(w0*255+.5,0,255)
    assert vb.itemsize==24
    with open(f'{A2}/assets/{tag}.mesh','wb') as f:
        f.write(struct.pack('<4sii',b'MSH2',len(V),F.size)); f.write(J.astype('<f4').tobytes()); f.write(vb.tobytes()); f.write(F.astype('<u2').tobytes())
    atlas=Image.open(f'face_{tag}.png').convert('RGBA'); atlas.save(f'{A2}/res/drawable-nodpi/face_{tag}.png')
    I=np.eye(4)[None].repeat(12,0)
    def shot(pose,root=(0,0,0),cell=0,yaw=0.3,pitch=0.0,S=700,F_=F,dense=4,bounds=None):
        P=skin(V,bi,w0,bone_mats(J,pose,root)); return render(P,F_,C,V,flag,atlas,cell,rect,yaw,pitch,S,dense,bounds)
    im,a=shot({},yaw=0.25,S=900,dense=5)
    Image.fromarray(np.dstack([im,a.astype(np.uint8)*255]),'RGBA').resize((im.shape[1]*2//3,600),Image.LANCZOS).save(f'{A2}/res/drawable-nodpi/preview_{tag}.png')
    head=(V[F].min(1)[:,1]>0.79)
    ims=[shot({},yaw=0,S=700)[0], shot({},yaw=np.pi,S=700)[0], shot({},yaw=0,S=700,F_=F[head],dense=10)[0], shot({},yaw=0.7,S=700,F_=F[head],dense=10,cell=2)[0]]
    W=sum(i.shape[1] for i in ims); o=Image.new('RGB',(W,700),(114,114,114)); xx=0
    for i in ims: o.paste(Image.fromarray(i),(xx,0)); xx+=i.shape[1]
    o.save(tag+'_final.png'); print(o.size)
