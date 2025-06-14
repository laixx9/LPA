package org.scut.ccnl.genomics;


// public class PreprocessEntity {
//     //Arrays.asList("id:"+id+":"+interval+":"+recordNum+":"+cigar_m+":"+cigar_i+":"+cigar_d+":"+cigar_s+":"+cigar_all);
//     private Integer id;
//     private Integer cigarI_D;
//     private Long interval;
//     private Long recordNum;
//     private Integer cigarS;

//     public PreprocessEntity(Integer id, Integer cigarI_D, Long interval, Long recordNum,Integer cigarS) {
//         this.id = id;
//         this.cigarI_D = cigarI_D;
//         this.interval = interval;
//         this.recordNum = recordNum;
//         this.cigarS = cigarS;
//     }

//     public Integer getId() {
//         return id;
//     }

//     public Integer getCigarI_D() {
//         return cigarI_D;
//     }

//     public Long getInterval() {
//         return interval;
//     }

//     public Long getRecordNum() {
//         return recordNum;
//     }

//     @Override
//     public String toString() {
//         return "PreprocessEntity{" +
//                 "id=" + id +
//                 ", cigarI_D=" + cigarI_D +
//                 ", interval=" + interval +
//                 ", recordNum=" + recordNum +
//                 '}';
//     }
// }

public class PreprocessEntity {
    //Arrays.asList("id:"+id+":"+interval+":"+recordNum+":"+cigar_m+":"+cigar_i+":"+cigar_d+":"+cigar_s+":"+cigar_all);
    private Integer id;
    
    private Long interval;
    private Long recordNum;
    private float time;

    public PreprocessEntity(Integer id, Long interval, Long recordNum, float time) {
        this.id = id;
        this.interval = interval;
        this.recordNum = recordNum;
        this.time = time;
    }

    public Integer getId() {
        return id;
    }

    public Long getInterval() {
        return interval;
    }

    public Long getRecordNum() {
        return recordNum;
    }

    public Float getTime() {
        return time;
    }

    @Override
    public String toString() {
        return "PreprocessEntity{" +
                "id=" + id +
                ", interval=" + interval +
                ", recordNum=" + recordNum +
                ", time=" + time +
                '}';
    }
}
