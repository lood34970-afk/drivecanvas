import math, collections
from dcroute import metrics, tang, resample
def route_pts(g,seq):
    pts=[]
    for e in seq:
        p=g.E[e]["pts"]; pts.extend(p if not pts else p[1:])
    return pts
def overlap_ratio(pts,tol=30.0,gap=400.0):
    rr=resample(pts,10); cum=[0.0]
    for i in range(1,len(rr)): cum.append(cum[-1]+math.dist(rr[i-1],rr[i]))
    cell=tol; b=collections.defaultdict(list)
    for i,p in enumerate(rr): b[(int(p[0]//cell),int(p[1]//cell))].append(i)
    L=cum[-1]; bad=0
    for i,p in enumerate(rr):
        X=int(p[0]//cell); Y=int(p[1]//cell); hit=False
        for dx in (-1,0,1):
            for dy in (-1,0,1):
                for j in b.get((X+dx,Y+dy),()):
                    sep=abs(cum[j]-cum[i]); sep=min(sep,L-sep)
                    if sep>gap and math.dist(p,rr[j])<=tol: hit=True; break
                if hit: break
            if hit: break
        if hit: bad+=1
    return bad/len(rr)
def analyze(g,res,tpl,W):
    seq=res["edges"]; pts=route_pts(g,seq)
    L=sum(g.E[e]["L"] for e in seq); T=sum(g.E[e]["time"] for e in seq)
    turns=0; lefts=0; td=0
    for a,b in zip(seq,seq[1:]):
        th=tang(g.E[a]["hout"],g.E[b]["hin"])
        if abs(th)>=45:
            turns+=1
            if th>0: lefts+=1; td+=20
            else: td+=8
    segc=collections.Counter()
    for e in seq:
        tw=g.E[e]["tw"]; segc[min(e,tw) if tw>=0 else e]+=1
    reuse=sum(g.E[e]["L"] for e in seq if segc[min(e,g.E[e]["tw"]) if g.E[e]["tw"]>=0 else e]>1)/L
    m=metrics(pts,tpl,W)
    return pts,dict(km=L/1000,eta=(T*1.25+td)/60,turns=turns,lefts=lefts,tpk=turns/(L/1000),reuse=reuse,ovl=overlap_ratio(pts),**m)
def ascii(g,pts,tpl,Wc=96,Hc=46,show_roads=True):
    xs=[p[0] for p in tpl]; ys=[p[1] for p in tpl]
    x0,x1,y0,y1=min(xs),max(xs),min(ys),max(ys); pad=0.06*(x1-x0); x0-=pad;x1+=pad;y0-=pad;y1+=pad
    s=max((x1-x0)/Wc,(y1-y0)/(Hc*2.0)); sy=s*2.0
    grid=[[" "]*Wc for _ in range(Hc)]
    def cell(p):
        X=int((p[0]-x0)/s); Y=int((y1-p[1])/sy)
        return (X,Y) if 0<=X<Wc and 0<=Y<Hc else None
    if show_roads:
        for e in g.E:
            for p in resample(e["pts"],s*0.5):
                c=cell(p)
                if c and grid[c[1]][c[0]]==" ": grid[c[1]][c[0]]=","
    for p in resample(tpl,s*0.5,closed=True):
        c=cell(p)
        if c: grid[c[1]][c[0]]="."
    for p in resample(pts,s*0.5):
        c=cell(p)
        if c: grid[c[1]][c[0]]="#" if grid[c[1]][c[0]]!="." else "@"
    return "\n".join("".join(r).rstrip() for r in grid)
def self_intersections(pts,cell=100.0):
    segs=[(pts[i],pts[i+1]) for i in range(len(pts)-1) if pts[i]!=pts[i+1]]
    n=len(segs); b=collections.defaultdict(list)
    for i,(a,c) in enumerate(segs):
        for X in range(int(min(a[0],c[0])//cell),int(max(a[0],c[0])//cell)+1):
            for Y in range(int(min(a[1],c[1])//cell),int(max(a[1],c[1])//cell)+1): b[(X,Y)].append(i)
    def cross(p1,p2,p3,p4):
        d1=(p4[0]-p3[0])*(p1[1]-p3[1])-(p4[1]-p3[1])*(p1[0]-p3[0]); d2=(p4[0]-p3[0])*(p2[1]-p3[1])-(p4[1]-p3[1])*(p2[0]-p3[0])
        d3=(p2[0]-p1[0])*(p3[1]-p1[1])-(p2[1]-p1[1])*(p3[0]-p1[0]); d4=(p2[0]-p1[0])*(p4[1]-p1[1])-(p2[1]-p1[1])*(p4[0]-p1[0])
        return d1*d2<0 and d3*d4<0
    found=set()
    for lst in b.values():
        for x in range(len(lst)):
            for y in range(x+1,len(lst)):
                i,j=lst[x],lst[y]
                if abs(i-j)<=1 or (i==0 and j==n-1): continue
                if (i,j) in found: continue
                if cross(segs[i][0],segs[i][1],segs[j][0],segs[j][1]): found.add((i,j))
    return len(found)
