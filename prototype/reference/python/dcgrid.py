import math
from dcgraph import Graph
def grid_graph(n=21,spacing=200.0,speed=40.0,oneway_cols=False,cls="tertiary"):
    g=Graph(); g.xy=[]; idx={}
    off=(n-1)*spacing/2
    for i in range(n):
        for j in range(n):
            idx[(i,j)]=len(g.xy); g.xy.append((i*spacing-off,j*spacing-off))
    E=[]
    def add(a,b,two):
        pa=g.xy[a]; pb=g.xy[b]; L=math.dist(pa,pb)
        E.append(dict(t=a,h=b,pts=[pa,pb],L=L,cls=cls,way=0,tw=-1))
        if two:
            E.append(dict(t=b,h=a,pts=[pb,pa],L=L,cls=cls,way=0,tw=len(E)-1)); E[-2]["tw"]=len(E)-1
    for i in range(n):
        for j in range(n):
            if i+1<n: add(idx[(i,j)],idx[(i+1,j)],True)
            if j+1<n:
                if oneway_cols and i%2==0: add(idx[(i,j)],idx[(i,j+1)],False)
                elif oneway_cols and i%2==1: add(idx[(i,j+1)],idx[(i,j)],False)
                else: add(idx[(i,j)],idx[(i,j+1)],True)
    g.E=E
    for e in E:
        pts=e["pts"]; e["hin"]=math.atan2(pts[1][1]-pts[0][1],pts[1][0]-pts[0][0]); e["hout"]=e["hin"]
        e["time"]=e["L"]/(speed/3.6); e["f"]=1.0
        k=max(1,int(math.ceil(e["L"]/40))); a,b=pts; e["sm"]=[(a[0]+(b[0]-a[0])*t/k,a[1]+(b[1]-a[1])*t/k) for t in range(k+1)]
    g.out=[[] for _ in g.xy]
    for i,e in enumerate(E): g.out[e["t"]].append(i)
    g.incomp=[True]*len(g.xy); g.ban=set(); g.bbox=((-off,-off),(off,off))
    return g
