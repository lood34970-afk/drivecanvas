import math, time, collections
import dcroute, dcana, dcsearch, dclegs
def S(st):
    e=0.5*min(1,st["nmsd"]/0.10)+0.3*min(1,st["p90"]/0.20)+0.2*min(1,max(st["tipd"],st["cuspd"])/0.12)
    return 100*(1-min(1,e))
def gate(st):
    r=[]
    if st["nmsd"]>0.035: r.append("nmsd")
    if st["p90"]>0.08: r.append("p90")
    if st["tipd"]>0.06: r.append("tip")
    if st["cuspd"]>0.06: r.append("cusp")
    if st["ovl"]>0.05: r.append("ovl")
    if st["reuse"]>0.05: r.append("reuse")
    if st["tpk"]>3.0: r.append("turns")
    if not (25<=st["eta"]<=50): r.append("eta")
    return r
def overlap_edges(g,seq,tol=30.0,gap=400.0):
    cum=[0.0]; mids=[]
    for e in seq:
        E=g.E[e]; p=E["pts"]; mids.append(((p[0][0]+p[-1][0])/2,(p[0][1]+p[-1][1])/2)); cum.append(cum[-1]+E["L"])
    L=cum[-1]; bad=set(); segc=collections.Counter()
    for e in seq:
        tw=g.E[e]["tw"]; segc[min(e,tw) if tw>=0 else e]+=1
    for i,e in enumerate(seq):
        tw=g.E[e]["tw"]
        if segc[min(e,tw) if tw>=0 else e]>1: bad.add(e); bad.add(tw)
        for j in range(len(seq)):
            sep=abs(cum[j]-cum[i]); sep=min(sep,L-sep)
            if sep>gap:
                for q in g.E[seq[j]]["sm"]:
                    if min(math.dist(q,p) for p in g.E[e]["sm"])<=tol: bad.add(e); break
    bad.discard(-1); return bad
def route_best(g,tpl,W,iters=2,**kw):
    res,err=dcroute.route(g,tpl,W,**kw)
    if err: return None,None,err
    pts,st=dcana.analyze(g,res,tpl,W); best=(res,pts,st); pen={}
    for it in range(iters):
        if st["ovl"]<=0.01 and st["reuse"]<=0.01: break
        for e in overlap_edges(g,res["edges"]): pen[e]=pen.get(e,1.0)*3.0
        r2,err2=dcroute.route(g,tpl,W,pen=pen,**kw)
        if err2: break
        p2,s2=dcana.analyze(g,r2,tpl,W); res,pts,st=r2,p2,s2
        if (len(gate(s2)),-S(s2)) < (len(gate(best[2])),-S(best[2])): best=(r2,p2,s2)
    return best[0],best[1],best[2]
def full_search(g,Ws,rots=range(-30,31,10),step=200,topk=10,verbose=False,**kw):
    rg=dcsearch.road_grid(g); (x0,y0),(x1,y1)=g.bbox; out=[]
    for W in Ws:
        cands=[]
        for rot in rots:
            cx=x0
            while cx<=x1:
                cy=y0
                while cy<=y1:
                    tpl=dcroute.place(cx,cy,W,rot); s=dcsearch.prescore(rg,tpl,W,g.bbox)
                    if s>0: cands.append((s,cx,cy,rot))
                    cy+=step
                cx+=step
        cands.sort(reverse=True)
        for s,cx,cy,rot in cands[:topk]:
            tpl=dcroute.place(cx,cy,W,rot); res,pts,st=route_best(g,tpl,W,**kw)
            if res is None: continue
            out.append(dict(pl=(cx,cy,W,rot),res=res,pts=pts,st=st,tpl=tpl,S=S(st),gate=gate(st)))
    return out
def pick(cands):
    ok=[c for c in cands if not c["gate"]]
    if not ok: return None,None
    shape=max(ok,key=lambda c:(c["S"]-0.3*abs(min(0,c["st"]["eta"]-30))))
    far=lambda c: math.dist(c["pl"][:2],shape["pl"][:2])>300 or c["pl"][2]!=shape["pl"][2] or c["pl"][3]!=shape["pl"][3]
    tf=[c for c in ok if c["st"]["eta"]<=shape["st"]["eta"]-5 and c["S"]>=shape["S"]-12 and far(c)]
    timef=min(tf,key=lambda c:(c["st"]["eta"]-0.5*c["S"])) if tf else None
    return shape,timef
