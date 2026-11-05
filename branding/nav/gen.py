from PIL import Image, ImageDraw
# Each icon is a square pixel grid scaled to the 24-unit viewport:
# 12x12 grids use 2-unit pixels, 24x24 grids (versions: isometric grass block) use 1-unit pixels.
G={
'versions':"""
........................
........................
........................
...........###..........
.........#######........
.......###########......
.....###############....
...###################..
.....###############....
...##..###########..##..
...####..#######..####..
...######..###..######..
.....######...######....
...##..#####.#####..##..
...####..###.###..####..
...######..#.#..######..
...########...########..
...###.#####.##.######..
.....#######.#######....
.......##.##.#####......
.........###.###........
...........#.#..........
........................
........................""",
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
def size(n): return len(rows(n))
def xml(n):
    paths=[]; N=size(n); P=24//N
    for y,r in enumerate(rows(n)):
        x=0
        while x<N:
            if r[x]=='#':
                s=x
                while x<N and r[x]=='#': x+=1
                paths.append(f"M{s*P},{y*P}h{(x-s)*P}v{P}h-{(x-s)*P}z")
            else: x+=1
    d=''.join(paths)
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- FCP Launcher bottom-nav icon ({n}). {N}x{N} pixel grid, {P} unit{"s" if P>1 else ""} per pixel. Single colour so BottomNavigationView tinting applies. -->
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
    assert size(n) in (12,24) and all(len(r)==size(n) for r in rows(n)), n
    open(f'ic_nav_{n}.xml','w').write(xml(n))
# Preview: 96px and true 24px, grey (#49454F, unselected) and dark (#1D1B20, selected) on #F3EDF7.
S=96; pad=24
img=Image.new('RGB',(pad+3*(S+pad), pad+2*(S+pad)+2*(24+pad)),'#F3EDF7')
d=ImageDraw.Draw(img)
def draw(n,ox,oy,px,col):
    c=px/size(n)
    for y,r in enumerate(rows(n)):
        for x,ch in enumerate(r):
            if ch=='#': d.rectangle([ox+x*c,oy+y*c,ox+(x+1)*c-1,oy+(y+1)*c-1],fill=col)
for j,col in enumerate(['#49454F','#1D1B20']):
    for i,n in enumerate(G):
        draw(n,pad+i*(S+pad),pad+j*(S+pad),S,col)
        draw(n,pad+i*(S+pad)+S//2-12,pad+2*(S+pad)+j*(24+pad),24,col)
import os
img.save(os.path.join(os.path.dirname(os.path.abspath(__file__)),'nav-icons-preview.png'))
