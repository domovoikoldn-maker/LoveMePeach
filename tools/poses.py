import numpy as np, sys
from PIL import Image
from build import *
tag=sys.argv[1]; d=np.load(tag+'_final.npz'); V,F,C,bi,w0,J,flag,rect=[d[k] for k in ('V','F','C','bi','w0','J','flag','rect')]
atlas=Image.open(f'face_{tag}.png').convert('RGBA')
P={ 'sit':({TH_L:(-1.5,0,0.06),TH_R:(-1.5,0,-0.06),SH_L:(0.12,0,0),SH_R:(0.12,0,0),SPINE:(-0.06,0,0),ARM_LU:(-0.25,0,0.12),ARM_RU:(-0.25,0,-0.12)},(0,-0.47,0),0),
    'dance':({HIPS:(0,0,0.10),SPINE:(0,0.1,-0.12),CHEST:(0,0.1,-0.08),HEAD:(0,0,0.15),ARM_LU:(-0.3,0,1.9),ARM_LL:(0,0,0.5),ARM_RU:(-0.2,0,-1.0),ARM_RL:(-0.9,0,0),TH_L:(-0.5,0,0.05),SH_L:(0.9,0,0)},(0,0.0,0),2),
    'jump':({TH_L:(-0.9,0,0.1),TH_R:(-0.9,0,-0.1),SH_L:(1.3,0,0),SH_R:(1.3,0,0),ARM_LU:(0,0,2.4),ARM_RU:(0,0,-2.4),HEAD:(-0.15,0,0)},(0,0.25,0),2),
    'bow':({SPINE:(0.45,0,0),CHEST:(0.35,0,0),HEAD:(0.25,0,0),ARM_LU:(-0.5,0,0),ARM_RU:(-0.5,0,0)},(0,0,0),1),
    'wave':({ARM_LU:(0,0,2.5),ARM_LL:(0,0,0.5),HEAD:(0,0,-0.1),SPINE:(0,0,-0.04)},(0,0,0),0),
    'crouch':({TH_L:(-1.0,0,0.1),TH_R:(-1.0,0,-0.1),SH_L:(1.7,0,0),SH_R:(1.7,0,0),SPINE:(0.35,0,0)},(0,-0.20,0),5)}
ims=[]
for n,(pose,root,cell) in P.items():
    Q=skin(V,bi,w0,bone_mats(J,pose,root)); im,_=render(Q,F,C,V,flag,atlas,cell,rect,yaw=0.9,S=520,dense=3,bounds=(np.array([-0.45,-0.5,-9]),np.array([0.45,1.3,9])))
    ims.append(Image.fromarray(im))
W=sum(i.width for i in ims); o=Image.new('RGB',(W,520),(114,114,114)); x=0
for i in ims: o.paste(i,(x,0)); x+=i.width
o.save(tag+'_poses.png'); print(o.size)
