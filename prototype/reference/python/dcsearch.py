import math, time
import dcroute, dcana
from dcroute import Grid, resample
def road_grid(g):
    pts=[]
    for e in g.E: pts.extend(e["sm"])
    return Grid(pts,50)
def prescore(rg,tpl,W,bbox,margin=150):
    (x0,y0),(x1,y1)=bbox
    for p in tpl[::5]:
        if not (x0+margin<=p[0]<=x1-margin and y0+margin<=p[1]<=y1-margin): return -1
    tau=0.04*W; hit=0; n=0
    for p in tpl[::4]:
        n+=1
        if rg.near(p,tau)<tau: hit+=1
    fe=0
    for p in (tpl[0],tpl[len(tpl)//2]):
        if rg.near(p,tau)<tau: fe+=1
    return hit/n+0.1*fe
def search(g,Ws=(2500,3000,3500),rots=range(-30,31,10),step=250,topk=8,verbose=True,**kw):
    rg=road_grid(g); (x0,y0),(x1,y1)=g.bbox
    results=[]
    for W in Ws:
        cands=[]
        for rot in rots:
            cx=x0
            while cx<=x1:
                cy=y0
                while cy<=y1:
                    tpl=dcroute.place(cx,cy,W,rot)
                    s=prescore(rg,tpl,W,g.bbox)
                    if s>0: cands.append((s,cx,cy,rot))
                    cy+=step
                cx+=step
        cands.sort(reverse=True)
        if verbose: print("W",W,"placements",len(cands),"top prescores",[round(c[0],3) for c in cands[:topk]])
        for s,cx,cy,rot in cands[:topk]:
            tpl=dcroute.place(cx,cy,W,rot)
            t0=time.time(); res,err=dcroute.route(g,tpl,W,**kw); dt=time.time()-t0
            if err:
                if verbose: print("  ",(round(cx),round(cy),W,rot),"pre",round(s,3),"ERR",err,"%.1fs"%dt)
                continue
            pts,st=dcana.analyze(g,res,tpl,W)
            results.append(((cx,cy,W,rot),s,st,pts,tpl,res))
            if verbose: print("  ",(round(cx),round(cy),W,rot),"pre",round(s,3),"%.1fs"%dt,{k:round(v,3) for k,v in st.items()})
    return results
