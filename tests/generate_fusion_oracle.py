"""Regenerate factual lookup outputs from a pinned local upstream checkout."""
from pathlib import Path
import shutil,subprocess,json,tempfile,sys
source=Path(sys.argv[1]).resolve()
expected="0a8b8a9198ed79fc8c4f898d53bba79cda76752b"
assert subprocess.check_output(["git","-C",str(source),"rev-parse","HEAD"],text=True).strip()==expected, "Use the pinned Fusion commit"
temporary=tempfile.TemporaryDirectory(prefix='fusion-oracle-')
root=Path(temporary.name)
base=root/'com/supermartijn642/fusion/texture/types/connecting';layout=base/'layouts';layout.mkdir(parents=True,exist_ok=True)
fields=['top','topRight','right','bottomRight','bottom','bottomLeft','left','topLeft']
(base/'TextureConnections.java').write_text('package com.supermartijn642.fusion.texture.types.connecting; public class TextureConnections { public boolean '+','.join(fields)+';}')
(layout/'ConnectingTextureLayoutHandler.java').write_text('package com.supermartijn642.fusion.texture.types.connecting.layouts; import com.supermartijn642.fusion.texture.types.connecting.TextureConnections; public class ConnectingTextureLayoutHandler { public abstract static class SimpleHandler { public SimpleHandler(int a,int b,int c){} protected abstract int connectionsIndex(TextureConnections c); protected abstract int[] getTilePos(TextureConnections c); }}')
names=['Full','Compact','Simple','Horizontal','Vertical']
for n in names: shutil.copyfile((source/'src/main/java/com/supermartijn642/fusion/texture/types/connecting/layouts')/(n+'LayoutHandler.java'),layout/(n+'LayoutHandler.java'))
assign=' '.join('c.'+f+'=(s & '+str(1<<[6,5,4,3,2,1,0,7][i])+')!=0;' for i,f in enumerate(fields))
(layout/'Oracle.java').write_text('''package com.supermartijn642.fusion.texture.types.connecting.layouts; import com.supermartijn642.fusion.texture.types.connecting.TextureConnections; public class Oracle { public static void main(String[] args){ ConnectingTextureLayoutHandler.SimpleHandler[] hs={new FullLayoutHandler(),new CompactLayoutHandler(),new SimpleLayoutHandler(),new HorizontalLayoutHandler(),new VerticalLayoutHandler()}; int[] widths={8,5,4,4,1}; for(int h=0;h<hs.length;h++) {for(int s=0;s<256;s++){TextureConnections c=new TextureConnections(); '''+assign+''' int[] p=hs[h].getTilePos(c);System.out.print((p[0]+p[1]*widths[h])+",");}System.out.println();}}}''')
subprocess.run(['java','com.sun.tools.javac.Main']+[str(p) for p in root.rglob('*.java')],check=True)
lines=subprocess.check_output(['java','-cp',str(root),'com.supermartijn642.fusion.texture.types.connecting.layouts.Oracle'],text=True).splitlines()
data={n.lower():[int(x) for x in l.strip(',').split(',')] for n,l in zip(names,lines)}
(Path(__file__).resolve().parent/'fixtures/fusion/layout-oracle.json').write_text(json.dumps(data,indent=2)+'\n')
print('Wrote all 256 states for five Fusion layouts')
