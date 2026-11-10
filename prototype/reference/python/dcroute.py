import math, heapq, collections
RC,CC,HH=1.0,0.9,2.3
def heart(nper=20,M=24):
    yc=math.sqrt(RC*RC-CC*CC); T=(0.0,-HH); dx,dy=CC-T[0],0-T[1]; d=math.hypot(dx,dy); L=math.sqrt(d*d-RC*RC)
    a=math.atan2(dy,dx)-math.asin(RC/d); Qr=(T[0]+L*math.cos(a),T[1]+L*math.sin(a)); Ql=(-Qr[0],Qr[1])
    fine=[]
    def line(p,q,n=400):
        for i in range(n): fine.append((p[0]+(q[0]-p[0])*i/n,p[1]+(q[1]-p[1])*i/n))
    def arc(c,a0,a1,n=800):
        for i in range(n): t=a0+(a1-a0)*i/n; fine.append((c[0]+RC*math.cos(t),c[1]+RC*math.sin(t)))
    line(T,Ql)
    aL0=math.atan2(Ql[1],Ql[0]+CC); aL1=math.atan2(yc,CC)
    if aL0<=aL1: aL0+=2*math.pi
    arc((-CC,0),aL0,aL1)
    aR0=math.atan2(yc,-CC); aR1=math.atan2(Qr[1],Qr[0]-CC)
    if aR1>aR0: aR1-=2*math.pi
    arc((CC,0),aR0,aR1)
    line(Qr,T); fine.append(T)
    cum=[0]
    for i in range(1,len(fine)): cum.append(cum[-1]+math.dist(fine[i-1],fine[i]))
    Ptot=cum[-1]; Nd=M*nper; out=[]; j=0
    for i in range(Nd):
        s=Ptot*i/Nd
        while cum[j+1]<s: j+=1
        u=(s-cum[j])/(cum[j+1]-cum[j]); out.append((fine[j][0]+(fine[j+1][0]-fine[j][0])*u,fine[j][1]+(fine[j+1][1]-fine[j][1])*u))
    return out
_HT={}
def place(cx,cy,W,rot,M=24,nper=20):
    key=(M,nper)
    if key not in _HT: _HT[key]=heart(nper,M)
    c=math.cos(math.radians(rot)); s=math.sin(math.radians(rot)); o=[]
    for x,y in _HT[key]:
        nx=x/3.8*W; ny=(y+0.65)/3.8*W
        o.append((cx+c*nx-s*ny, cy+s*nx+c*ny))
    return o
def segd2(p,a,b):
    vx,vy=b[0]-a[0],b[1]-a[1]; wx,wy=p[0]-a[0],p[1]-a[1]; L2=vx*vx+vy*vy
    t=0 if L2==0 else max(0,min(1,(wx*vx+wy*vy)/L2)); dx=wx-t*vx; dy=wy-t*vy
    return dx*dx+dy*dy
def pld2(p,pl): return min(segd2(p,pl[i],pl[i+1]) for i in range(len(pl)-1))
class Grid:
    def __init__(s,pts,cell=50.0):
        s.c=cell; s.b=collections.defaultdict(list)
        for p in pts: s.b[(int(p[0]//cell),int(p[1]//cell))].append(p)
    def near(s,p,r):
        c=s.c; best=r*r; f=False
        for X in range(int((p[0]-r)//c),int((p[0]+r)//c)+1):
            for Y in range(int((p[1]-r)//c),int((p[1]+r)//c)+1):
                for q in s.b.get((X,Y),()):
                    d=(q[0]-p[0])**2+(q[1]-p[1])**2
                    if d<=best: best=d; f=True
        return math.sqrt(best) if f else r
def resample(pl,step,closed=False):
    pts=list(pl)+([pl[0]] if closed else []); o=[pts[0]]
    for i in range(len(pts)-1):
        a=pts[i]; b=pts[i+1]; L=math.dist(a,b)
        if L==0: continue
        n=max(1,int(math.ceil(L/step)))
        for k in range(1,n+1): o.append((a[0]+(b[0]-a[0])*k/n,a[1]+(b[1]-a[1])*k/n))
    return o
def metrics(route,tpl,W):
    rr=resample(route,10); tt=resample(tpl,10,closed=True)
    gr=Grid(rr,50); gt=Grid(tt,50); big=0.3*W
    dRT=[gt.near(p,big) for p in rr[::2]]; dTR=[gr.near(p,big) for p in tt[::2]]
    m=(sum(dRT)/len(dRT)+sum(dTR)/len(dTR))/2
    allv=sorted(dRT+dTR); p90=allv[int(0.9*len(allv))]
    tip=tpl[0]; cusp=tpl[len(tpl)//2]
    return dict(nmsd=m/W,p90=p90/W,tipd=gr.near(tip,big)/W,cuspd=gr.near(cusp,big)/W)
def tang(h1,h2):
    d=math.degrees(h2-h1)
    while d>180: d-=360
    while d<=-180: d+=360
    return d
def route(g,tpl,W,M=24,nper=20,alpha=4.0,sigf=0.05,dmaxf=0.15,rdf=0.06,tR=5,tL=12,tS=40,pen=None,useclsf=True,rfeat=None):
    sig=sigf*W; dmax2=(dmaxf*W)**2; R=rdf*W
    Rk=[R]*M
    if rfeat is not None: Rk[M//2]=rfeat*W
    anchors=[tpl[k*nper] for k in range(M)]
    pieces=[tpl[k*nper:(k+1)*nper+1] if k<M-1 else tpl[k*nper:]+[tpl[0]] for k in range(M)]
    best=None
    for v,(x,y) in enumerate(g.xy):
        if not g.incomp[v] or not g.out[v]: continue
        d=math.hypot(x-anchors[0][0],y-anchors[0][1])
        if best is None or d<best[0]: best=(d,v)
    if best[0]>R*1.5: return None,"start too far %d" % best[0]
    v0=best[1]; d2c={}; tc={}
    def d2(e,k):
        key=(e,k)
        if key not in d2c:
            pc=pieces[k]; sm=g.E[e]["sm"]; d2c[key]=sum(pld2(p,pc) for p in sm)/len(sm)
        return d2c[key]
    def touches(e,j):
        key=(e,j)
        if key not in tc:
            a=anchors[j]; pts=g.E[e]["pts"]; tc[key]=min(segd2(a,pts[i],pts[i+1]) for i in range(len(pts)-1))<=Rk[j]*Rk[j]
        return tc[key]
    def adv(e,k):
        while k+1<M and touches(e,k+1): k+=1
        return k
    def ecost(e,k,k2):
        dd=min(d2(e,k),d2(e,k2)) if k2!=k else d2(e,k)
        if dd>dmax2: return None
        E=g.E[e]; c=E["time"]*(E["f"] if useclsf else 1.0)*(1+alpha*dd/(sig*sig))
        if pen and e in pen: c*=pen[e]
        return c
    dist={}; pred={}; heap=[]; pops=0
    for e in g.out[v0]:
        k2=adv(e,0); c=ecost(e,0,k2)
        if c is None: continue
        if c<dist.get((e,k2),1e18): dist[(e,k2)]=c; pred[(e,k2)]=None; heapq.heappush(heap,(c,e,k2))
    goal=None
    while heap:
        c,e,k=heapq.heappop(heap); pops+=1
        if c>dist.get((e,k),1e18): continue
        E=g.E[e]; v=E["h"]
        if k==M-1 and v==v0: goal=(e,k); break
        pe=pred[(e,k)]
        for e2 in g.out[v]:
            if e2==E["tw"] or (e,e2) in g.ban: continue
            E2=g.E[e2]; th=tang(E["hout"],E2["hin"])
            if abs(th)>165: continue
            if E["L"]<40 and pe is not None and abs(tang(g.E[pe[0]]["hout"],E2["hin"]))>160: continue
            tcost=0 if abs(th)<=30 else (tS if abs(th)>150 else (tL if th>0 else tR))
            k2=adv(e2,k); ec=ecost(e2,k,k2)
            if ec is None: continue
            nc=c+ec+tcost
            if nc<dist.get((e2,k2),1e18): dist[(e2,k2)]=nc; pred[(e2,k2)]=(e,k); heapq.heappush(heap,(nc,e2,k2))
    if goal is None: return None,"no path pops %d" % pops
    seq=[]; s=goal
    while s is not None: seq.append(s[0]); s=pred[s]
    seq.reverse()
    return dict(edges=seq,v0=v0,pops=pops),None
