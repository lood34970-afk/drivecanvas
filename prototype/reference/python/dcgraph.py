import os, glob, collections, math
import xml.etree.ElementTree as ET
REG={"sejong":(36.478,127.235,36.523,127.290),"ilsan":(37.640,126.745,37.685,126.800),"bundang":(37.345,127.095,37.390,127.150)}
CLS={"trunk":(60,1.2),"trunk_link":(40,1.0),"primary":(50,1.0),"primary_link":(35,1.0),"secondary":(45,1.0),"secondary_link":(30,1.0),"tertiary":(40,1.0),"tertiary_link":(30,1.0),"unclassified":(30,1.2),"residential":(25,1.3)}
BAD={"private","no","permit","delivery","destination","agricultural","forestry","customers","emergency","military","bus","psv"}
MPD=6371000.0*math.pi/180
def load(base,reg):
    nodes={}; ways={}; rels=[]
    for f in sorted(glob.glob(os.path.join(base,reg,"tile_*.osm"))):
        for el in ET.parse(f).getroot():
            if el.tag=="node": nodes[int(el.get("id"))]=(float(el.get("lat")),float(el.get("lon")))
            elif el.tag=="way":
                tags={t.get("k"):t.get("v") for t in el.iter("tag")}
                if "highway" in tags: ways[int(el.get("id"))]=(tags,[int(n.get("ref")) for n in el.iter("nd")])
            elif el.tag=="relation":
                tags={t.get("k"):t.get("v") for t in el.iter("tag")}
                if tags.get("type")=="restriction": rels.append((tags,[(m.get("type"),int(m.get("ref")),m.get("role")) for m in el.iter("member")]))
    return nodes,ways,rels
class Graph: pass
def build(base,reg,cls=CLS):
    nodes,ways,rels=load(base,reg)
    la0,lo0,la1,lo1=REG[reg]; olat=(la0+la1)/2; olon=(lo0+lo1)/2; cl=math.cos(math.radians(olat))
    P=lambda ll:((ll[1]-olon)*MPD*cl,(ll[0]-olat)*MPD)
    kept={}
    for wid,(t,nds) in ways.items():
        h=t["highway"]
        if h not in cls: continue
        acc=t.get("motorcar") or t.get("motor_vehicle") or t.get("vehicle") or t.get("access") or "yes"
        if acc in BAD or t.get("area")=="yes" or t.get("oneway") in ("reversible","alternating"): continue
        nds=[n for n in nds if n in nodes]
        if len(nds)<2: continue
        ow=t.get("oneway")
        d = 1 if ow in ("yes","true","1") else -1 if ow in ("-1","reverse") else 0 if ow in ("no","false","0") else (1 if t.get("junction") in ("roundabout","circular") else 0)
        kept[wid]=(h,d,nds)
    use=collections.Counter()
    for h,d,nds in kept.values():
        for n in nds: use[n]+=1
        use[nds[0]]+=1; use[nds[-1]]+=1
    gn=[n for n,c in use.items() if c>=2]; idx={n:i for i,n in enumerate(gn)}
    g=Graph(); g.xy=[P(nodes[n]) for n in gn]; g.region=reg
    g.bbox=(P((la0,lo0)),P((la1,lo1)))
    E=[]
    for wid,(h,d,nds) in kept.items():
        cur=[nds[0]]
        for n in nds[1:]:
            cur.append(n)
            if n in idx:
                a,b=idx[cur[0]],idx[n]
                pts=[P(nodes[m]) for m in cur]
                L=sum(math.dist(pts[i],pts[i+1]) for i in range(len(pts)-1))
                if L>0.5:
                    if d>=0: E.append(dict(t=a,h=b,pts=pts,L=L,cls=h,way=wid))
                    if d<=0: E.append(dict(t=b,h=a,pts=pts[::-1],L=L,cls=h,way=wid))
                    if d==0: E[-1]["tw"]=len(E)-2; E[-2]["tw"]=len(E)-1
                cur=[n]
    N=len(gn); out=[[] for _ in range(N)]; inn=[[] for _ in range(N)]
    for i,e in enumerate(E): out[e["t"]].append(i); inn[e["h"]].append(i)
    order=[]; seen=[False]*N
    for s in range(N):
        if seen[s]: continue
        stack=[(s,0)]; seen[s]=True
        while stack:
            v,i=stack[-1]
            if i<len(out[v]):
                stack[-1]=(v,i+1); w=E[out[v][i]]["h"]
                if not seen[w]: seen[w]=True; stack.append((w,0))
            else: stack.pop(); order.append(v)
    comp=[-1]*N; c=0
    for s in reversed(order):
        if comp[s]>=0: continue
        st=[s]; comp[s]=c
        while st:
            v=st.pop()
            for ei in inn[v]:
                w=E[ei]["t"]
                if comp[w]<0: comp[w]=c; st.append(w)
        c+=1
    big=collections.Counter(comp).most_common(1)[0][0]
    keepE=[i for i,e in enumerate(E) if comp[e["t"]]==big and comp[e["h"]]==big]
    remap={o:i for i,o in enumerate(keepE)}
    g.E=[]
    for o in keepE:
        e=dict(E[o]); e["tw"]=remap.get(e.get("tw",-1),-1)
        pts=e["pts"]; e["hin"]=math.atan2(pts[1][1]-pts[0][1],pts[1][0]-pts[0][0]); e["hout"]=math.atan2(pts[-1][1]-pts[-2][1],pts[-1][0]-pts[-2][0])
        sp,f=cls[e["cls"]]; e["time"]=e["L"]/(sp/3.6); e["f"]=f
        sm=[]
        for i in range(len(pts)-1):
            a=pts[i]; b=pts[i+1]; n=max(1,int(math.ceil(math.dist(a,b)/40)))
            for k in range(n): sm.append((a[0]+(b[0]-a[0])*k/n,a[1]+(b[1]-a[1])*k/n))
        sm.append(pts[-1]); e["sm"]=sm
        g.E.append(e)
    g.out=[[] for _ in range(N)]
    for i,e in enumerate(g.E): g.out[e["t"]].append(i)
    g.incomp=[comp[v]==big for v in range(N)]
    wIn=collections.defaultdict(list); wOut=collections.defaultdict(list)
    for i,e in enumerate(g.E): wIn[(e["way"],e["h"])].append(i); wOut[(e["way"],e["t"])].append(i)
    g.ban=set()
    for tags,mem in rels:
        r=tags.get("restriction","")
        fr=[m[1] for m in mem if m[2]=="from" and m[0]=="way"]; to=[m[1] for m in mem if m[2]=="to" and m[0]=="way"]; via=[m[1] for m in mem if m[2]=="via" and m[0]=="node"]
        if len(fr)!=1 or len(to)!=1 or len(via)!=1 or via[0] not in idx: continue
        if fr[0]==to[0]: continue
        v=idx[via[0]]; ins=wIn.get((fr[0],v),[]); outs=wOut.get((to[0],v),[])
        if r.startswith("no_"):
            for a in ins:
                for b in outs: g.ban.add((a,b))
        elif r.startswith("only_") and outs:
            for a in ins:
                for b in g.out[v]:
                    if b not in outs: g.ban.add((a,b))
    return g
