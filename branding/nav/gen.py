from PIL import Image, ImageDraw
P=2  # 12x12 grid, 2 units/cell in 24 viewport
G={
'versions':"""
............
.##########.
.##########.
.##########.
.#.##..##.#.
.#..#.....#.
.#........#.
.#.....#..#.
.#.#......#.
.#.....#..#.
.##########.
............""",
'accounts':"""
............
.##########.
.##########.
.#........#.
.##########.
.#..####..#.
.#..####..#.
.##########.
.###....###.
.##########.
.##########.
............""",
'about':"""
............
....####....
..########..
..###..###..
.####..####.
.##########.
.###...####.
.####..####.
..###..###..
..##....##..
....####....
............""",
}
def rows(n): return [r for r in G[n].strip('\n').split('\n')]
def xml(n):
    paths=[]
    for y,r in enumerate(rows(n)):
        x=0
        while x<12:
            if r[x]=='#':
                s=x
                while x<12 and r[x]=='#': x+=1
                paths.append(f"M{s*P},{y*P}h{(x-s)*P}v{P}h-{(x-s)*P}z")
            else: x+=1
    d=''.join(paths)
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- FCP Launcher bottom-nav icon ({n}). 12x12 pixel grid, 2 units per pixel. Single colour so BottomNavigationView tinting applies. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FF000000"
        android:pathData="{d}" />
</vector>
'''
for n in G:
    assert all(len(r)==12 for r in rows(n)) and len(rows(n))==12, n
    open(f'ic_nav_{n}.xml','w').write(xml(n))
S=96; pad=24
img=Image.new('RGB',(pad+3*(S+pad), pad+2*(S+pad)+20),'#FFFBFE')
d=ImageDraw.Draw(img)
for j,col in enumerate(['#49454F','#1B5E20']):
    for i,n in enumerate(G):
        ox=pad+i*(S+pad); oy=pad+j*(S+pad)
        if j==1: d.rounded_rectangle([ox-12,oy-4,ox+S+12,oy+S+4],radius=30,fill='#C8E6C9')
        c=S/12
        for y,r in enumerate(rows(n)):
            for x,ch in enumerate(r):
                if ch=='#': d.rectangle([ox+x*c,oy+y*c,ox+(x+1)*c-1,oy+(y+1)*c-1],fill=col)
for i,n in enumerate(G):
    # also actual 24px size
    ox=pad+i*(S+pad)+S//2-12; oy=pad+2*(S+pad)-14
    for y,r in enumerate(rows(n)):
        for x,ch in enumerate(r):
            if ch=='#': d.rectangle([ox+x*2,oy+y*2,ox+x*2+1,oy+y*2+1],fill='#49454F')
img.save('/workspace/fcp-launcher/branding/nav-icons-preview.png')
