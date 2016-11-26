package cn.edu.nju.cs.screencamera;


import java.util.Arrays;
import java.util.BitSet;
import java.util.Map;

import cn.edu.nju.cs.screencamera.ReedSolomon.ReedSolomonException;

/**
 * Created by zhantong on 2016/11/25.
 */

public class ShiftCode {
    public static final int OVERLAP_CLEAR_WHITE=0;
    public static final int OVERLAP_CLEAR_BLACK=1;
    public static final int OVERLAP_BLACK_TO_WHITE=2;
    public static final int OVERLAP_WHITE_TO_BLACK=3;

    MediateBarcode mediateBarcode;
    int refWhite;
    int refBlack;
    int threshold;
    int binaryThreshold;
    int overlapSituation;
    Map<DecodeHintType,?> hints;

    public ShiftCode(MediateBarcode mediateBarcode, Map<DecodeHintType,?> hints){
        this.mediateBarcode=mediateBarcode;
        this.hints=hints;
        processBorderRight();
        processBorderLeft();
        System.out.println("refWhite: "+refWhite+" refBlack: "+refBlack+" threshold: "+threshold);
        System.out.println("overlap: "+overlapSituation);
        //getClearRawContent();
        //getMixedContent();
    }
    public int getTransmitFileLengthInBytes() throws CRCCheckException{
        int[] content=mediateBarcode.getContent(mediateBarcode.districts.get(Districts.BORDER).get(District.UP));
        BitSet data=new BitSet();
        for(int i=0;i<content.length;i++){
            if(content[i]>binaryThreshold){
                data.set(i);
            }
        }
        int transmitFileLengthInBytes=Utils.bitsToInt(data,32,0);
        int crc=Utils.bitsToInt(data,8,32);
        Utils.crc8Check(transmitFileLengthInBytes,crc);
        return transmitFileLengthInBytes;
    }
    public void processBorderRight(){
        int[] content=mediateBarcode.getContent(mediateBarcode.districts.get(Districts.BORDER).get(District.RIGHT));
        int sumWhite=0,sumBlack=0;
        int maxWhite=0,minWhite=255;
        int maxBlack=0,minBlack=255;
        for(int i=0;i<content.length;i+=2){
            int currentWhite=content[i];
            int currentBlack=content[i+1];
            sumWhite+=currentWhite;
            sumBlack+=currentBlack;
            if(maxWhite<currentWhite){
                maxWhite=currentWhite;
            }
            if(minWhite>currentWhite){
                minWhite=currentWhite;
            }
            if(maxBlack<currentBlack){
                maxBlack=currentBlack;
            }
            if(minBlack>currentBlack){
                minBlack=currentBlack;
            }
        }
        int numHalfPoints=content.length/2;
        refWhite=sumWhite/numHalfPoints;
        refBlack=sumBlack/numHalfPoints;
        binaryThreshold=(refWhite+refBlack)/2;
        threshold=(maxWhite+maxBlack-minWhite-minBlack)/2;
    }
    public void processBorderLeft(){
        int[] content=mediateBarcode.getContent(mediateBarcode.districts.get(Districts.BORDER).get(District.LEFT));
        int mixIndicatorUp=content[0];
        int mixIndicatorDown=content[content.length-1];
        int refBlackExpand=refBlack+threshold;
        int refWhiteExpand=refWhite-threshold;
        if((mixIndicatorUp<=refBlackExpand)&&(mixIndicatorDown<=refBlackExpand)){
            overlapSituation=OVERLAP_CLEAR_BLACK;
        }else if((mixIndicatorUp>=refWhiteExpand)&&(mixIndicatorDown>=refWhiteExpand)){
            overlapSituation=OVERLAP_CLEAR_WHITE;
        }else if(mixIndicatorUp>mixIndicatorDown){
            overlapSituation=OVERLAP_WHITE_TO_BLACK;
        }else{
            overlapSituation=OVERLAP_BLACK_TO_WHITE;
        }
        System.out.println("mix indicator up: "+mixIndicatorUp+" mix indicator down: "+mixIndicatorDown+" refWhiteEx: "+refWhiteExpand+" refBlackEx: "+refBlackExpand);
    }
    public int[] getClearRawContent(){
        Zone zone=mediateBarcode.districts.get(Districts.MAIN).get(District.MAIN);
        ShiftBlock block=(ShiftBlock) zone.getBlock();
        int[] content=mediateBarcode.getContent(zone);
        int[] rawData=new int[zone.widthInBlock*zone.heightInBlock];
        int step=block.getNumSamplePoints();
        int offset=0;
        int rawDataPos=0;
        for(int y=0;y<zone.heightInBlock;y++) {
            for (int x = 0; x < zone.widthInBlock; x++) {
                boolean isWhite=(overlapSituation==OVERLAP_CLEAR_WHITE);
                int value=block.getClear(isWhite,x,y,content,offset);
                offset+=step;
                rawData[rawDataPos]=value;
                rawDataPos++;
            }
        }
        return rawData;
    }
    public BitSet getMixedRawContent(){
        overlapSituation=OVERLAP_WHITE_TO_BLACK;
        Zone zone=mediateBarcode.districts.get(Districts.MAIN).get(District.MAIN);
        ShiftBlock block=(ShiftBlock) zone.getBlock();
        int[] content=mediateBarcode.getContent(zone);
        int step=block.getNumSamplePoints();
        int offset=0;
        for(int y=0;y<zone.heightInBlock;y++) {
            for (int x = 0; x < zone.widthInBlock; x++) {
                boolean isFormerWhite=(overlapSituation==OVERLAP_WHITE_TO_BLACK);
                int[] values=block.getMixed(isFormerWhite,threshold,x,y,content,offset);
                offset+=step;
                System.out.print(Arrays.toString(values)+" ");
            }
            break;
        }
        return null;
    }
    public byte[] rSDecode(int[] rawContent,Zone zone) throws ReedSolomonException {
        int eCSize=-1;
        int numEC=-1;
        int rawBitsPerUnit=zone.getBlock().getBitsPerUnit();
        if(hints!=null){
            if(hints.containsKey(DecodeHintType.RS_ERROR_CORRECTION_SIZE)){
                eCSize=Integer.parseInt(hints.get(DecodeHintType.RS_ERROR_CORRECTION_SIZE).toString());
            }else {
                throw new IllegalArgumentException();
            }
            if(hints.containsKey(DecodeHintType.RS_ERROR_CORRECTION_LEVEL)){
                float eCLevel=Float.parseFloat(hints.get(DecodeHintType.RS_ERROR_CORRECTION_LEVEL).toString());
                numEC=calcEcNum(zone,eCSize,eCLevel);
            }else{
                throw new IllegalArgumentException();
            }
        }
        int rawDataLengthInUnit=zone.widthInBlock*zone.heightInBlock-eCSize*numEC/rawBitsPerUnit;
        int[] rsEncodedDataPart=Utils.changeNumBitsPerInt(rawContent,0,rawDataLengthInUnit,rawBitsPerUnit,eCSize);
        int[] rsEncodeDataRepair=Utils.changeNumBitsPerInt(rawContent,rawDataLengthInUnit,rawContent.length-rawDataLengthInUnit,rawBitsPerUnit,eCSize);
        int[] rSEncodedData=new int[rsEncodedDataPart.length+rsEncodeDataRepair.length];
        System.out.println(rawContent.length+" "+rsEncodedDataPart.length+" "+rsEncodeDataRepair.length+" "+rSEncodedData.length);
        System.arraycopy(rsEncodedDataPart,0,rSEncodedData,0,rsEncodedDataPart.length);
        System.arraycopy(rsEncodeDataRepair,0,rSEncodedData,rsEncodedDataPart.length,rsEncodeDataRepair.length);
        System.out.println(Arrays.toString(rSEncodedData));
        Utils.rSDecode(rSEncodedData,numEC,eCSize);

        byte[] rSDecodedData=Utils.intArrayToByteArray(rSEncodedData,rSEncodedData.length-numEC,eCSize);
        return rSDecodedData;
    }
    protected static int calcEcNum(Zone zone,int rSEcSize,float rSEcLevel){
        return ((int)((zone.getBlock().getBitsPerUnit()*zone.widthInBlock*zone.heightInBlock/rSEcSize)*rSEcLevel))/2*2;
    }
}
