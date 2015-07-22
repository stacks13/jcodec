package org.jcodec.codecs.h264.encode;

import static org.jcodec.codecs.h264.H264Const.MB_BLK_OFF_LEFT;
import static org.jcodec.codecs.h264.H264Const.MB_BLK_OFF_TOP;
import static org.jcodec.codecs.h264.io.model.MBType.I_16x16;
import static org.jcodec.codecs.h264.io.model.MBType.P_16x16;

import org.jcodec.codecs.h264.H264Const;
import org.jcodec.codecs.h264.decode.BlockInterpolator;
import org.jcodec.codecs.h264.decode.CoeffTransformer;
import org.jcodec.codecs.h264.io.CAVLC;
import org.jcodec.codecs.h264.io.model.MBType;
import org.jcodec.codecs.h264.io.model.SeqParameterSet;
import org.jcodec.codecs.h264.io.write.CAVLCWriter;
import org.jcodec.common.io.BitWriter;
import org.jcodec.common.model.Picture;

/**
 * This class is part of JCodec ( www.jcodec.org ) This software is distributed
 * under FreeBSD License
 * 
 * Encodes macroblock as P16x16
 * 
 * @author Stanislav Vitvitskyy
 */
public class MBEncoderP16x16 {

    private CAVLC[] cavlc;
    private SeqParameterSet sps;
    private Picture ref;

    public MBEncoderP16x16(SeqParameterSet sps, Picture ref, CAVLC[] cavlc) {
        this.sps = sps;
        this.cavlc = cavlc;
        this.ref = ref;
    }

    public void encodeMacroblock(Picture pic, int mbX, int mbY, BitWriter out, Picture outMB, int qp, int qpDelta) {
        int cw = pic.getColor().compWidth[1];
        int ch = pic.getColor().compHeight[1];

        if (sps.num_ref_frames > 1) {
            int refIdx = decideRef();
            CAVLCWriter.writeTE(out, refIdx, sps.num_ref_frames - 1);
        }

        // Prediction based on the previous MVs
        int mvpx = 0, mvpy = 0;

        // Motion estimation for the current macroblock
        int[] mv = mvEstimate(pic, mbX, mbY, mvpx, mvpy);
        CAVLCWriter.writeSE(out, mv[0] - mvpx); // mvx
        CAVLCWriter.writeSE(out, mv[1] - mvpy); // mvy

        Picture mbRef = Picture.create(16, 16, sps.chroma_format_idc), mb = Picture.create(16, 16,
                sps.chroma_format_idc);

        BlockInterpolator.getBlockLuma(ref, mbRef, 0, (mbX << 6) + mv[0], (mbY << 6) + mv[1], 16, 16);

        BlockInterpolator.getBlockChroma(ref.getPlaneData(1), ref.getPlaneWidth(1), ref.getPlaneHeight(1),
                mbRef.getPlaneData(1), 0, mbRef.getPlaneWidth(1), (mbX << 6) + mv[0], (mbY << 6) + mv[1], 8, 8);
        BlockInterpolator.getBlockChroma(ref.getPlaneData(2), ref.getPlaneWidth(2), ref.getPlaneHeight(2),
                mbRef.getPlaneData(2), 0, mbRef.getPlaneWidth(2), (mbX << 6) + mv[0], (mbY << 6) + mv[1], 8, 8);

        MBEncoderHelper.takeSubtract(pic.getPlaneData(0), pic.getPlaneWidth(0), pic.getPlaneHeight(0), mbX << 4,
                mbY << 4, mb.getPlaneData(0), mbRef.getPlaneData(0), 16, 16);
        MBEncoderHelper.takeSubtract(pic.getPlaneData(1), pic.getPlaneWidth(1), pic.getPlaneHeight(1), mbX << (4 - cw),
                mbY << (4 - ch), mb.getPlaneData(1), mbRef.getPlaneData(1), 16 >> cw, 16 >> ch);
        MBEncoderHelper.takeSubtract(pic.getPlaneData(2), pic.getPlaneWidth(2), pic.getPlaneHeight(2), mbX << (4 - cw),
                mbY << (4 - ch), mb.getPlaneData(2), mbRef.getPlaneData(2), 16 >> cw, 16 >> ch);

        int codedBlockPattern = getCodedBlockPattern();
        CAVLCWriter.writeUE(out, H264Const.CODED_BLOCK_PATTERN_INTER_COLOR_INV[codedBlockPattern]);

        CAVLCWriter.writeSE(out, qpDelta);

        luma(pic, mb.getPlaneData(0), mbX, mbY, out, qp);
        chroma(pic, mb.getPlaneData(1), mb.getPlaneData(2), mbX, mbY, out, qp);

        MBEncoderHelper.putBlk(outMB.getPlaneData(0), mb.getPlaneData(0), mbRef.getPlaneData(0), 4, 0, 0, 16, 16);
        MBEncoderHelper.putBlk(outMB.getPlaneData(1), mb.getPlaneData(1), mbRef.getPlaneData(1), 4 - cw, 0, 0,
                16 >> cw, 16 >> ch);
        MBEncoderHelper.putBlk(outMB.getPlaneData(2), mb.getPlaneData(2), mbRef.getPlaneData(2), 4 - cw, 0, 0,
                16 >> cw, 16 >> ch);
    }

    private int getCodedBlockPattern() {
        return 47;
    }

    private int[] mvEstimate(Picture pic, int mbX, int mbY, int mvpx, int mvpy) {
        return new int[] { 0, 0 };
    }

    /**
     * Decides which reference to use
     * 
     * @return
     */
    private int decideRef() {
        return 0;
    }

    private void luma(Picture pic, int[] pix, int mbX, int mbY, BitWriter out, int qp) {
        int[][] ac = new int[16][16];
        for (int i = 0; i < ac.length; i++) {
            for (int j = 0; j < H264Const.PIX_MAP_SPLIT_4x4[i].length; j++) {
                ac[i][j] = pix[H264Const.PIX_MAP_SPLIT_4x4[i][j]];
            }
            CoeffTransformer.fdct4x4(ac[i]);
        }

        writeAC(0, mbX, mbY, out, mbX << 2, mbY << 2, ac, qp);

        for (int i = 0; i < ac.length; i++) {
            CoeffTransformer.dequantizeAC(ac[i], qp);
            CoeffTransformer.idct4x4(ac[i]);
            for (int j = 0; j < H264Const.PIX_MAP_SPLIT_4x4[i].length; j++)
                pix[H264Const.PIX_MAP_SPLIT_4x4[i][j]] = ac[i][j];
        }
    }

    private void chroma(Picture pic, int[] pix1, int[] pix2, int mbX, int mbY, BitWriter out, int qp) {
        int cw = pic.getColor().compWidth[1];
        int ch = pic.getColor().compHeight[1];
        int[][] ac1 = new int[16 >> (cw + ch)][16];
        int[][] ac2 = new int[16 >> (cw + ch)][16];
        for (int i = 0; i < ac1.length; i++) {
            for (int j = 0; j < H264Const.PIX_MAP_SPLIT_2x2[i].length; j++)
                ac1[i][j] = pix1[H264Const.PIX_MAP_SPLIT_2x2[i][j]];
        }
        for (int i = 0; i < ac2.length; i++) {
            for (int j = 0; j < H264Const.PIX_MAP_SPLIT_2x2[i].length; j++)
                ac2[i][j] = pix2[H264Const.PIX_MAP_SPLIT_2x2[i][j]];
        }
        MBEncoderI16x16.chromaResidual(pic, mbX, mbY, out, qp, ac1, ac2, cavlc[1], cavlc[2], P_16x16, P_16x16);

        for (int i = 0; i < ac1.length; i++) {
            for (int j = 0; j < H264Const.PIX_MAP_SPLIT_2x2[i].length; j++)
                pix1[H264Const.PIX_MAP_SPLIT_2x2[i][j]] = ac1[i][j];
        }
        for (int i = 0; i < ac2.length; i++) {
            for (int j = 0; j < H264Const.PIX_MAP_SPLIT_2x2[i].length; j++)
                pix2[H264Const.PIX_MAP_SPLIT_2x2[i][j]] = ac2[i][j];
        }
    }

    private void writeAC(int comp, int mbX, int mbY, BitWriter out, int mbLeftBlk, int mbTopBlk, int[][] ac, int qp) {
        for (int i = 0; i < ac.length; i++) {
            int blkI = H264Const.BLK_INV_MAP[i];
            CoeffTransformer.quantizeAC(ac[blkI], qp);
            // System.out.print("Luma coeff: ");
            // for(int j = 0; j < 16; j++)
            // System.out.print(ac[i][j] + ",");
            // System.out.println();
            // TODO: calc here
            cavlc[comp].writeACBlock(out, mbLeftBlk + MB_BLK_OFF_LEFT[i], mbTopBlk + MB_BLK_OFF_TOP[i], P_16x16,
                    P_16x16, ac[blkI], H264Const.totalZeros16, 0, 16, CoeffTransformer.zigzag4x4);
        }
    }
}