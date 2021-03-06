package hex.la;

import water.*;
import water.H2O.H2OCountedCompleter;
import water.fvec.*;
import water.fvec.Chunk;
import water.fvec.Frame;
import water.fvec.NewChunk;
import water.fvec.NewChunk.Value;
import water.util.Utils;

import javax.xml.bind.attachment.AttachmentUnmarshaller;
import java.util.Arrays;
import java.util.Iterator;

/**
* Created by tomasnykodym on 11/13/14.
*/
public class DMatrix  {

  public static Frame transpose(Frame src){
    int nchunks = Math.min(src.numCols(),4*H2O.NUMCPUS*H2O.CLOUD.size());
    long [] espc = new long[nchunks+1];
    int rpc = (src.numCols() / nchunks);
    int rem = (src.numCols() % nchunks);
    Arrays.fill(espc, rpc);
    for (int i = 0; i < rem; ++i) ++espc[i];
    long sum = 0;
    for (int i = 0; i < espc.length; ++i) {
      long s = espc[i];
      espc[i] = sum;
      sum += s;
    }
    return transpose(src, new Frame(new Vec(Vec.newKey(),espc).makeZeros((int)src.numRows())));
  }

  public static Frame transpose(Frame src, Frame tgt){
    if(src.numRows() != tgt.numCols() || src.numCols() != tgt.numRows())
      throw new IllegalArgumentException("dimension do not match!");
    for(Vec v:src.vecs()) {
      if (v.isEnum())
        throw new IllegalArgumentException("transpose can only be applied to all-numeric frames (representing a matrix)");
      if(v.length() > 1000000)
        throw new IllegalArgumentException("too many rows, transpose only works for frames with < 1M rows.");
    }
    new TransposeTsk(tgt).doAll(src);
    return tgt;
  }

  public static Frame mmul2(Frame x, Frame y) {
    if(x.numCols() != y.numRows())
      throw new IllegalArgumentException("dimensions do not match! x.numcols = " + x.numCols() + ", y.numRows = " + y.numRows());
    // make the target frame which is compatible with left hand side (same number of rows -> keep it's vector group and espc)
    Frame z = new Frame(x.anyVec().makeZeros(y.numCols()));
    // make transpose co-located with y
    x = transpose(x,new Frame(y.anyVec().makeZeros((int)x.numRows())));
    x.reloadVecs();
    new MatrixMulTsk2(x,y).doAll(z);
    z.reloadVecs();
    x.delete();
    return z;
  }

  // second version of matrix multiply -> no global transpose, just locally transpose chunks (rows compressed instead of column compressed)
  public static Frame mmul(Frame x, Frame y) {
    if(x.numCols() != y.numRows())
      throw new IllegalArgumentException("dimensions do not match! x.numcols = " + x.numCols() + ", y.numRows = " + y.numRows());
    // make the target frame which is compatible with left hand side (same number of rows -> keep it's vector group and espc)
    Frame z = new Frame(x.anyVec().makeZeros(y.numCols()));
    new MatrixMulTsk(y).doAll(Utils.append(x.vecs(),z.vecs()));
    z.reloadVecs();
    return z;
  }

  /**
   * Matrix multiplication task which takes input of two matrices, X and Y and produces matrix X %*% Y.
   */
  public static class MatrixMulTsk extends MRTask2<MatrixMulTsk> {
    private final Frame  _Y;

    public MatrixMulTsk(Frame Y) {
      _Y = Y;
    }

    public void map(Chunk [] chks) {
      // split to input/output (can't use NewChunks/outputFrame here, writing to chunks in forked off task)
      Chunk [] ncs = Arrays.copyOfRange(chks,chks.length-_Y.numCols(),chks.length);
      chks = Arrays.copyOf(chks,chks.length-_Y.numCols());
      NewChunk [] urows = new NewChunk[chks[0]._len]; // uncompressed rows
      for(int i = 0; i < urows.length; ++i)
        urows[i] = new NewChunk(null,-1,0);
      for(int i = 0; i < chks.length; ++i) {
        Chunk c = chks[i];
        NewChunk nc = c.inflate();
        Iterator<Value> it = nc.values();
        while(it.hasNext()) {
          Value v = it.next();
          int ri = v.rowId0();
          urows[ri].addZeros(i - urows[ri]._len);
          v.add2Chunk(urows[ri]);
        }
      }
      Chunk [] crows = new Chunk[urows.length];
      for(int i = 0; i < urows.length; ++i) {
        urows[i].addZeros(chks.length - urows[i]._len);
        crows[i] = urows[i].compress();
        urows[i] = null;
      }
      // got transposed chunks...now do the multiply over y
      addToPendingCount(1);
      new ChunkMulTsk(this,crows,ncs,_fs).asyncExec(_Y);
    }
  }


  private static class Rows extends Iced {
    Chunk [] _rows; // compressed rows of a chunk of X
    Rows(Chunk [] chks){_rows = chks;}

    @Override
    public AutoBuffer write(AutoBuffer ab) {
      ab.put4(_rows.length);
      for(int i = 0; i < _rows.length; ++i) {
        ab.put4(_rows[i].frozenType());
        ab.putA1(_rows[i]._mem);
      }
      return ab;
    }

    @Override public Rows read(AutoBuffer ab){
      _rows = new Chunk[ab.get4()];
      for(int i = 0; i < _rows.length; ++i)
        (_rows[i] = (Chunk)TypeMap.newFreezable(ab.get4())).read(new AutoBuffer(ab.getA1()));
      return this;
    }
  }

  private static class ChunkMulTsk extends MRTask2<ChunkMulTsk> {
    final transient Chunk [] _ncs;
    final transient Futures _fs;
    Rows _rows;

    public ChunkMulTsk(H2OCountedCompleter cmp, Chunk[] rows, Chunk[] ncs, Futures fs) {
      super(cmp);
      _fs = fs;
      _rows = new Rows(rows); _ncs = ncs;
    }

    double [][] _res;

    @Override public void map(Chunk [] chks){
      final Chunk [] rows = _rows._rows;
      _res = new double [rows.length][];
      for(int i = 0; i < _res.length; ++i)
        _res[i] = MemoryManager.malloc8d(chks.length);
      final int off = (int)chks[0]._start;
      assert off == chks[0]._start;
      for(int i = 0; i < rows.length; ++i) {
        final Chunk rc = rows[i];
        for(int j = 0; j < chks.length; ++j) {
          final Chunk cc = chks[j];
          for(int k = 0; k < chks[j]._len; k = chks[j].nextNZ(k)) {
            _res[i][j] += rc.at0(k+off) * cc.at0(k);
          }
        }
      }
    }
    @Override protected void closeLocal(){ _rows = null;}
    @Override public void reduce(ChunkMulTsk m) {
      for (int i = 0; i < _res.length; ++i)
        Utils.add(_res[i], m._res[i]);
    }

    @Override public void postGlobal(){
      for(int i = 0; i < _res.length; ++i)
        for(int j = 0; j < _res[i].length; ++j)
          _ncs[j].set0(i,_res[i][j]);
      for(Chunk c:_ncs)
        c.close(c.cidx(),_fs);

    }

  }

  /**
   * Matrix multiplication task which takes input of two matrices, t(X) and Y and produces matrix X %*% Y.
   */
  public static class MatrixMulTsk2 extends MRTask2<MatrixMulTsk> {
    private final Frame _X, _Y;

    public MatrixMulTsk2(Frame X, Frame Y) {
      _X = X;
      _Y = Y;
    }

    public void map(Chunk [] chks) {
      addToPendingCount(chks.length);
      int iStart = (int)chks[0]._start;
      int iEnd = iStart + chks[0]._len;
      Vec[] xs = Arrays.copyOfRange(_X.vecs(), iStart, iEnd);
      for(int j = 0; j < chks.length; ++j)
        new VecMulTsk(this,chks[j],_fs).asyncExec(Utils.append(new Vec[]{_Y.vec(j)}, xs));
    }
  }

  private static final class VecMulTsk extends MRTask2<VecMulTsk> {
    final transient Chunk _c;
    final transient Futures _fs;
    double [] _res;
    public VecMulTsk(H2OCountedCompleter cmp, Chunk c, Futures fs) {
      super(cmp);
      _c = c; _fs = fs;
    }
    @Override
    public void map(Chunk [] chks) {
      _res = MemoryManager.malloc8d(chks.length-1);
      for(int i = 0; i < chks[0]._len; i = chks[0].nextNZ(i)) {
        final double y = chks[0].at0(i);
        for(int j = 1; j < chks.length; ++j)
          _res[j-1] += y*chks[j].at0(i);
      }
    }
    @Override
    public void reduce(VecMulTsk v) { Utils.add(_res,v._res);}

    @Override
    public void postGlobal() {
      assert _c.len() == _res.length;
      for(int i = 0; i < _res.length; ++i)
        _c.set0(i, _res[i]);
      _c.close(_c.cidx(),_fs);
    }
  }

  public static class TransposeTsk extends MRTask2<TransposeTsk> {
    final Frame _tgt;

    public TransposeTsk(Frame tgt){ _tgt = tgt;}
    public void map(Chunk [] chks) {
      final Frame tgt = _tgt;
      long [] espc = tgt.anyVec()._espc;
      NewChunk [] tgtChunks = new NewChunk[chks[0]._len];
      int colStart = (int)chks[0]._start;
      for(int i = 0; i < espc.length-1; ++i) {
        for(int j = 0; j < tgtChunks.length; ++j)
          tgtChunks[j] = new NewChunk(tgt.vec(j + colStart), i);
        for (int c = ((int) espc[i]); c < (int) espc[i + 1]; ++c) {
          NewChunk nc = chks[c].inflate();
          Iterator<Value> it = nc.values();
          while (it.hasNext()) {
            Value v = it.next();
            NewChunk t  = tgtChunks[v.rowId0()];
            t.addZeros(c-(int)espc[i] - t.len());
            v.add2Chunk(t);
          }
        }
        for(NewChunk t:tgtChunks) {
          t.addZeros((int)(espc[i+1] - espc[i]) - t.len());
          t.close(_fs);
        }
      }
    }
  }
}
