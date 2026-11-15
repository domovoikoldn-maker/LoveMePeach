import numpy as np, sys
from scipy.sparse import coo_matrix, csr_matrix
from scipy.sparse.csgraph import dijkstra
from seg import face_adj
from paint import vnormals, hexc, sstep

def edge_graph(V,F):
    """Граф вершин: вес ребра = длина * штраф за складку (вдоль складок границы «дешёвые» для разреза)."""
    t=V[F]; fn=np.cross(t[:,1]-t[:,0],t[:,2]-t[:,0]); fn/=np.linalg.norm(fn,axis=1,keepdims=True)+1e-12; fc=t.mean(1)
    nf=len(F); e=np.r_[F[:,[0,1]],F[:,[1,2]],F[:,[2,0]]]; fid=np.tile(np.arange(nf),3)
    key=np.sort(e,axis=1); k=key[:,0].astype(np.int64)*10**7+key[:,1]; o=np.argsort(k); k=k[o]; fid=fid[o]; key=key[o]
    same=k[1:]==k[:-1]; a=fid[:-1][same]; b=fid[1:][same]; ed=key[:-1][same]
    ang=np.degrees(np.arccos(np.clip((fn[a]*fn[b]).sum(1),-1,1))); conc=((fc[b]-fc[a])*fn[a]).sum(1)>0
    L=np.linalg.norm(V[ed[:,0]]-V[ed[:,1]],axis=1)
    pen=np.where(conc,60.0,12.0)*(np.minimum(ang,90)/40.0)**2
    w=L*(1+pen); n=len(V)
    G=coo_matrix((np.r_[w,w],(np.r_[ed[:,0],ed[:,1]],np.r_[ed[:,1],ed[:,0]])),shape=(n,n)).tocsr()
    # вогнутость вершины (для запекания теней в складках)
    cv=np.zeros(n); cnt=np.zeros(n); s=np.where(conc,ang,-ang)
    np.add.at(cv,ed[:,0],s); np.add.at(cv,ed[:,1],s); np.add.at(cnt,ed[:,0],1); np.add.at(cnt,ed[:,1],1)
    return G, cv/np.maximum(cnt,1), ed

def grow(G,seeds):
    """seeds: {метка: маска}. Каждая вершина получает метку ближайшего (по весам) семени."""
    n=G.shape[0]; src=[]; lab_of=[]
    for l,m in seeds.items():
        idx=np.where(m)[0]; src.append(idx); lab_of.append(np.full(len(idx),l))
    src=np.concatenate(src); lab_of=np.concatenate(lab_of); lut=np.zeros(n,int); lut[src]=lab_of
    dist,pred,srcs=dijkstra(G,directed=False,indices=src,min_only=True,return_predecessors=True)
    return lut[srcs]
