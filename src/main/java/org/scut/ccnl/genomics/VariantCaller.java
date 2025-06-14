package org.scut.ccnl.genomics;

import com.fasterxml.jackson.databind.ObjectMapper;
import ai.catboost.CatBoostModel;
import ai.catboost.CatBoostPredictions;
import htsjdk.samtools.CigarElement;
import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMSequenceDictionary;
import htsjdk.samtools.util.PeekableIterator;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFHeader;
import org.apache.commons.cli.CommandLine;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.log4j.Logger;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.util.LongAccumulator;
import org.broadinstitute.gatk.engine.GATKVCFUtils;
import org.broadinstitute.gatk.engine.GenomeAnalysisEngine;
import org.broadinstitute.gatk.engine.datasources.providers.LocusShardDataProvider;
import org.broadinstitute.gatk.engine.datasources.rmd.ReferenceOrderedDataSource;
import org.broadinstitute.gatk.engine.executive.WindowMakerIterator;
import org.broadinstitute.gatk.engine.filters.*;
import org.broadinstitute.gatk.engine.io.DirectOutputTracker;
import org.broadinstitute.gatk.engine.io.stubs.VariantContextWriterStub;
import org.broadinstitute.gatk.engine.iterators.MisencodedBaseQualityReadTransformer;
import org.broadinstitute.gatk.engine.iterators.ReadTransformer;
import org.broadinstitute.gatk.engine.iterators.ReadTransformingIterator;
import org.broadinstitute.gatk.engine.traversals.TraverseActiveRegions;
import org.broadinstitute.gatk.tools.walkers.haplotypecaller.HCMappingQualityFilter;
import org.broadinstitute.gatk.tools.walkers.haplotypecaller.HaplotypeCaller;
import org.broadinstitute.gatk.utils.GenomeLocParser;
import org.broadinstitute.gatk.utils.refdata.RefMetaDataTracker;
import org.broadinstitute.gatk.utils.sam.GATKSAMRecord;
import org.broadinstitute.gatk.utils.sam.ReadUtils;
import org.scut.ccnl.genomics.io.VariantsSparkSink;
import org.scut.ccnl.genomics.io.dbsnp.ImpalaVCFCodec;
import org.scut.ccnl.genomics.io.dbsnp.VCFDBCacheCodecFactory;
import org.scut.ccnl.genomics.io.dbsnp.VCFDBCodec;
import org.scut.ccnl.genomics.io.dbsnp.VCFDBCodecFactory;
import org.scut.ccnl.genomics.io.fasta.CachingDistributedFastaSequenceFile;
import org.seqdoop.hadoop_bam.util.SAMHeaderReader;
import scala.Tuple2;

import java.io.OutputStream;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.io.File;
import java.net.URL;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class VariantCaller {

    final protected static Logger logger = Logger.getLogger(VariantCaller.class);

    // 将 Iterator<GATKSAMRecord> 通过变异检测转换称为 Iterator<VariantContext>
    public static Iterator<VariantContext> getVariantContexts(
            int id,
            Iterator<GATKSAMRecord> gatksamRecordIterator,
            GlobalArgument globalArgument,
            LongAccumulator accumulator
    ) throws Exception {
        setEnviVariable(globalArgument);

        GenomeLocParser genomeLocParser = new GenomeLocParser(globalArgument.sequenceDictionary);
        genomeLocParser.setMRUCachingSAMSequenceDictionary();

        // WindowMakerIterator为WindowMaker去掉interval参数的实现
        // 还是需要迭代,因为一个partition里可能有多个染色体的数据
        GATKRecordSubListIterator listIterator = new GATKRecordSubListIterator(
                applyDecoratingIterators(gatksamRecordIterator), genomeLocParser);
        Spliterators.spliteratorUnknownSize(listIterator, Spliterator.SORTED | Spliterator.ORDERED);

        //封装，以便不同的实现
        Iterable<SubIteratorItem> subIteratorItemIterable = () -> listIterator;
        Stream<SubIteratorItem> subIteratorItemStream = StreamSupport.stream(subIteratorItemIterable.spliterator(), false);
        return subIteratorItemStream.flatMap(subIteratorItemToVariant(id, globalArgument, genomeLocParser, accumulator)).iterator();

    }

    public static Function<SubIteratorItem, Stream<VariantContext>> subIteratorItemToVariant(
            int id,
            GlobalArgument globalArgument,
            GenomeLocParser genomeLocParser,
            LongAccumulator accumulator
    ) {
        return subIteratorItem -> {
            List<VariantContext> listMapData = new ArrayList<>();

            try (CachingDistributedFastaSequenceFile fastaSequenceFile
                    = CachingDistributedFastaSequenceFile.getFastaSeqFile(globalArgument); HaplotypeCaller walker = new HaplotypeCaller(); VCFDBCodec vcfdbCodec = globalArgument.DBSNP_CACHE
                    ? VCFDBCacheCodecFactory.getVCFDBCodec(globalArgument)
                    : VCFDBCodecFactory.getVCFDBCodec(globalArgument)) {
                if (subIteratorItem.getContig() == null) {
                    System.out.println("contig null:" + subIteratorItem.getStart());
                    PeekableIterator<GATKSAMRecord> tempIterator = (PeekableIterator<GATKSAMRecord>) subIteratorItem.getIterator();
                    while (tempIterator.hasNext() && tempIterator.peek().getContig() == null) {
                        tempIterator.next();
                    }
                    return listMapData.stream();
                }
                int itemstart = subIteratorItem.getStart();
                // engine
                GenomeAnalysisEngine engine = new GenomeAnalysisEngine();
                engine.setGenomeLocParser(genomeLocParser);

                walker.setToolkit(engine);
                engine.setWalker(walker);
                //全部的初始化
                walker.initializeForActiveRegionAndAssemble(new WalkerArguments(globalArgument, fastaSequenceFile));

                TraverseActiveRegions tar = new TraverseActiveRegions();
                tar.initialize(engine, walker);

                WindowMakerIterator windowMakerIterator = new WindowMakerIterator(subIteratorItem,
                        genomeLocParser, genomeLocParser.createGenomeLoc(subIteratorItem.getContig(),
                                itemstart,
                                subIteratorItem.getStop()), globalArgument.sampleList);
                List<ReferenceOrderedDataSource> rodDataSources = new ArrayList<>();
                //倒数第二个参数为Reference数据源
                LocusShardDataProvider dataProvider = new LocusShardDataProvider(null,
                        windowMakerIterator.getSourceInfo(),
                        genomeLocParser,
                        windowMakerIterator.getLocus(),
                        windowMakerIterator,
                        fastaSequenceFile,
                        rodDataSources);

                long flapmap_startTime = System.currentTimeMillis();

                Iterator<MapData> activeRegionIterator = tar.new ActiveRegionIterator(dataProvider);

                long dbsnpTime = 0;

                while (activeRegionIterator.hasNext()) {
                    MapData mapData = activeRegionIterator.next();
                    if (mapData.activeRegion.isActive()) {

                        RefMetaDataTracker tracker;
                        long dbsnpTempStart = System.nanoTime();
                        tracker = vcfdbCodec.parserLocation(mapData.activeRegion.getLocation(), genomeLocParser);
                        long dbsnpTempEnd = System.nanoTime();
                        dbsnpTime += (dbsnpTempEnd - dbsnpTempStart);

                        listMapData.addAll(walker.map(mapData.activeRegion, tracker));
                    }
                }
                accumulator.add(dbsnpTime);

                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                String formattedDate = sdf.format(new Date());
                long flapmap_stopTime = System.currentTimeMillis();
                System.out.println("[" + formattedDate + "]" + "id:" + id + " start:" + subIteratorItem.getOriginalStart() + ":" + ((TraverseActiveRegions.ActiveRegionIterator) activeRegionIterator).getZeroLocation() + ":" + "runtime:" + (flapmap_stopTime - flapmap_startTime) + "ms");
            } catch (Exception e) {
                e.printStackTrace();
            }
            return listMapData.stream();
        };
    }

    public static Iterator<String> getCoverage(
            int id,
            Iterator<GATKSAMRecord> gatksamRecordIterator,
            GlobalArgument globalArgument) throws Exception {

        GenomeLocParser genomeLocParser = new GenomeLocParser(globalArgument.sequenceDictionary);
        genomeLocParser.setMRUCachingSAMSequenceDictionary();

        long coverage = 0L;
        long inteval = 0L;

        // 还是需要迭代,因为一个partition里可能有多个染色体的数据
        GATKRecordSubListIterator listIterator = new GATKRecordSubListIterator(
                new PeekableLastIterator<>(applyDecoratingIterators(gatksamRecordIterator)), genomeLocParser);

        while (listIterator.hasNext()) {
            SubIteratorItem subIteratorItem = listIterator.next();
            int start = subIteratorItem.getOriginalStart();

            TraverseActiveRegions tar = new TraverseActiveRegions();
            WindowMakerIterator windowMakerIterator = new WindowMakerIterator(subIteratorItem,
                    genomeLocParser, genomeLocParser.createGenomeLoc(subIteratorItem.getContig(),
                            subIteratorItem.getOriginalStart(),
                            subIteratorItem.getStop()), globalArgument.sampleList);

            //倒数第二个参数为Reference数据源
            LocusShardDataProvider dataProvider = new LocusShardDataProvider(null,
                    windowMakerIterator.getSourceInfo(),
                    genomeLocParser,
                    windowMakerIterator.getLocus(),
                    windowMakerIterator,
                    null,
                    null);

            TraverseActiveRegions.CoverageGetter coverageGetter = tar.new CoverageGetter(dataProvider);
            coverage += coverageGetter.getCoverage();
            int end = ((PeekableLastIterator<GATKSAMRecord>) subIteratorItem.getIterator()).getLast().getEnd();
            inteval += (end - start);

        }

        List<String> results = Arrays.asList("id:" + id + ":" + coverage + ":" + inteval + ":" + (inteval != 0L ? (coverage / inteval) : 0));

        return results.iterator();
    }

    public static Iterator<String> getPreprocessInfo(
            int id,
            Iterator<GATKSAMRecord> gatksamRecordIterator) throws Exception {

        PeekableIterator<GATKSAMRecord> recordIterator
                = new PeekableIterator<>(VariantCaller.applyDecoratingIterators(gatksamRecordIterator));
        if (!recordIterator.hasNext()) {
            return Arrays.asList("id:" + id + ":" + 0 + ":" + 0 + ":" + 0 + ":" + 0 + ":" + 0 + ":" + 0 + ":" + 0).iterator();
        }

        //Chromosome Number,Start Position,End Position,Block Number,Length,Total Number of Reads,Total Quality Score,Total Variants,Sum of Quality Scores * Variants Count,Variant Density,Coverage,time,Reference Genome
        long recordNum = 0;
        long cigar_m = 0, cigar_i = 0, cigar_d = 0, cigar_n = 0, cigar_s = 0, cigar_h = 0, cigar_p = 0, cigar_all = 0;
        long interval = 0;
        long qualitySum = 0, qualityMuitplyCigar = 0;

        int start = recordIterator.peek().getStart(), lastPos = 0;
        String contig = recordIterator.peek().getContig();
        GATKSAMRecord last = recordIterator.peek();

        while (recordIterator.hasNext()) {
            GATKSAMRecord current = recordIterator.next();
            if (current.getContig() == null) {
                continue;
            }

            int currentStart = current.getStart();

            // 当不同染色体时，需要更新
            if (!contig.equals(current.getContig())) {
                interval += (last.getStart() - start);
                start = currentStart;
                contig = current.getContig();
            }
            last = current;
            recordNum++;
            int phredScore = current.getMappingQuality();
            qualitySum += phredScore;
            Object mnValue = current.getAttribute("NM");
            int mnIntValue = 0;

            if (mnValue != null) {
                try {

                    mnIntValue = (int) mnValue;
                } catch (NumberFormatException e) {
                    mnIntValue = 0;
                }
            }
            qualityMuitplyCigar += mnIntValue * phredScore;
            cigar_all += mnIntValue;
        }

        // TODO:  需要考虑多染色体
        interval += last.getStart() - start;
        lastPos = last.getStart();
        ObjectMapper objectMapper = new ObjectMapper();
        File file = new File("/disk/sata00/lxx/lxx/data/label_encoder_mapping.json");
        File file1 = new File("/disk/sata00/lxx/lxx/data/label_encoder_mapping_RG.json");

        Map<String, Integer> labelMapping = objectMapper.readValue(file, Map.class);
        Map<String, Integer> labelMappingRG = objectMapper.readValue(file1, Map.class);
        int contigValue = labelMapping.get(contig) != null ? labelMapping.get(contig) : 1;
        int RGValue = labelMappingRG.get(Arguments.FASTA) != null ? labelMappingRG.get(Arguments.FASTA) : 0;
        String[] features = {
            "Chromosome Number",
            "Start Position",
            "End Position",
            "Block Number",
            "Length",
            "Total Number of Reads",
            "Total Quality Score",
            "Total Variants",
            "Sum of Quality Scores * Variants Count",
            "Variant Density",
            "Coverage",
            "Reference Genome"
        };

        // 构造特征数据
        float[] data = {contigValue, start, lastPos, id, interval, recordNum, qualitySum, cigar_all, qualityMuitplyCigar, (float) cigar_all / interval, (float) recordNum / interval, RGValue};

        //Dataset dataset = Dataset.createFromMat(new float[][] {data}, new String[]{"float"});
        CatBoostModel model = CatBoostModel.loadModel("/disk/sata00/lxx/lxx/data/catboost_model.cbm");
        CatBoostPredictions prediction = model.predict(data, features);

        // 获取预测值
        double time = prediction.get(0, 0);
        StringBuilder sb = new StringBuilder();
        sb.append("id:").append(id).append(":").append(interval).append(":").append(recordNum).append(":")
                .append(time).append(":");

        List<String> results = Arrays.asList(sb.toString());

        return results.iterator();
    }

    public static void setPreprocessInfo(List<String> infos) {
        int infosSize = infos.size();
        //Set<Integer> priority = new HashSet<>(Arguments.additionSet);
        List<Integer> priority = new ArrayList<>(Arguments.additionSet);
        int i = 0;
        while (i < infosSize && infos.get(i).startsWith("#")) {
            i++;
        }

        List<PreprocessEntity> entities = new ArrayList<>(infosSize);

        // TODO: 2018/1/5 temp
        for (; i < infosSize; i++) {
            String[] flags = infos.get(i).split(":");
            long interval = Long.valueOf(flags[2]);
            long recordNum = Long.valueOf(flags[3]);
            // TODO: 2017/12/29 这两个属性都为0原因暂时不明
            //if(interval == 0 && recordNum == 0){
            //System.out.println("fliter:" + Integer.valueOf(flags[1]));
            //		continue;
            //}
            entities.add(new PreprocessEntity(Integer.valueOf(flags[1]),
                    interval,
                    recordNum,
                    Float.valueOf(flags[4])));
        }
        int entitiesSize = entities.size();
        //System.out.println("entitiesSize:"+entitiesSize);
        Arguments.VALID_PARTITION_NUM = entitiesSize;

        //（cigarI+cigarD）的前7%，interval的后5%，Record的后5%，
        // 先按cigarI+D排序，取前7%
        // 默认排序是升序
        int base = 0;
        int treshhold = 0;
        double percente = entitiesSize * 0.09;
        switch (entitiesSize) {
            case 1 <= entitiesSize <= 500:
                treshhold = 900000;
            case 500 < entitiesSize <= 1500:
                treshhold = 1500000;
            case entitiesSize > 1500:
                treshhold = 3000000;
                break;
            default:
                treshhold = 3000000;
        }
        entities.sort(Comparator.comparing(PreprocessEntity::getTime).reversed());
        for (int j = 0; j < entitiesSize; j++) {
            priority.add(entities.get(j).getId());
            if (entities.get(j).getTime() > treshhold) {
                base++;
            }
        }
        if (percente > base) {
        Arguments.BASE = percente;
        } else {
            Arguments.BASE = base;
        }
        Arguments.BASE = max(percente, base);
        // entities.sort(Comparator.comparing(PreprocessEntity::getInterval));
        // for(int j = 0; j < fivePercent; j++){
        //     priority.add(entities.get(j).getId());
        // }

        // entities.sort(Comparator.comparing(PreprocessEntity::getRecordNum));
        // for(int j = 0; j < sevenPercent; j++){
        //     priority.add(entities.get(j).getId());
        //}
//        entities.sort(Comparator.comparing(PreprocessEntity::getCigarI_D));
//        for(int j = 0; j < eightPercent; j++){
//            priority.add(entities.get(entitiesSize -1 - j).getId());
//        }
//
//        entities.sort(Comparator.comparing(PreprocessEntity::getInterval));
//        for(int j = 0; j < eightPercent; j++){
//            priority.add(entities.get(entitiesSize -1 - j).getId());
//        }
//        for(int j = 0; j < fivePercent; j++){
//            priority.add(entities.get(j).getId());
//        }
//
//        entities.sort(Comparator.comparing(PreprocessEntity::getRecordNum));
//        for(int j = 0; j < eightPercent; j++){
//            priority.add(entities.get(j).getId());
//        }
        //System.out.println(priority);
        Arguments.PARTITIONS_TO_CHANGE = priority;
    }

    public static SAMSequenceDictionary staticSequenceDictionary;

    public static void printVariantContexts(
            JavaSparkContext jsc,
            Configuration conf,
            JavaRDD<VariantContext> vcfrdd,
            GlobalArgument globalArgument,
            CommandLine commandLine) throws Exception {
        // engine
        GenomeAnalysisEngine engine = new GenomeAnalysisEngine();

        GenomeLocParser genomeLocParser = new GenomeLocParser(globalArgument.sequenceDictionary);
        genomeLocParser.setMRUCachingSAMSequenceDictionary();

        engine.setGenomeLocParser(genomeLocParser);
        HaplotypeCaller walker = new HaplotypeCaller();
        engine.setWalker(walker);
        walker.setToolkit(engine);

        VCFHeader header = GATKVCFUtils.withUpdatedContigs(
                new VCFHeader(walker.getHeadInfo(globalArgument), globalArgument.sampleList), engine);

        // 设置输出
        String outputFile = globalArgument.OUTPUT_NAME;
        Path path = new Path(globalArgument.OUTPUT_NAME);
        FileSystem fs = null;//path.getFileSystem(conf);

        OutputStream outputStream;
        if (outputFile.startsWith("file:///")) {
            fs = FileSystem.getLocal(conf);
        } else {
            fs = path.getFileSystem(conf);
        }
        // 如果文件存在，就先删除
        if (fs.exists(path)) {
            fs.delete(path, true);
            logger.warn(globalArgument.OUTPUT_NAME + " exist, delete and then create new file");
        }

        if (commandLine.hasOption("s")) {
            staticSequenceDictionary = globalArgument.sequenceDictionary;
            List<VariantContext> variantContextList = new ArrayList<>(vcfrdd.collect());
            variantContextList.sort(header.getVCFRecordComparator());

            outputStream = fs.create(path);

            VariantContextWriterStub vcfWriter = new VariantContextWriterStub(null, outputStream, Arrays.asList());
            DirectOutputTracker outputTracker = new DirectOutputTracker();
            outputTracker.addOutput(vcfWriter);
            vcfWriter.writeHeader(header);

            VariantContext last = null;

            for (VariantContext current : variantContextList) {
                if (last != null && last.getStart() == current.getStart() && last.getContig().equals(current.getContig())) {
                    continue;
                }
                vcfWriter.add(current);
                last = current;
            }

            outputStream.close();
        } else {
            VariantsSparkSink.saveAsShardedHadoopFiles(jsc, conf, globalArgument.OUTPUT_NAME, vcfrdd, header);
        }
    }

    public static Iterator<GATKSAMRecord> applyDecoratingIterators(Iterator<GATKSAMRecord> wrappedIterator) {
        // TODO wu:hc的默认参数
        Collection<ReadFilter> filters = Arrays.asList(new HCMappingQualityFilter(), new UnmappedReadFilter(),
                new NotPrimaryAlignmentFilter(), new DuplicateReadFilter(), new FailsVendorQualityCheckFilter(),
                new MappingQualityUnavailableFilter(), new BadCigarFilter());
        MisencodedBaseQualityReadTransformer readTransformer = new MisencodedBaseQualityReadTransformer();
        readTransformer.defalutSetter();
        return applyDecoratingIterators(false, false, wrappedIterator,
                false, filters, Arrays.asList(readTransformer), (byte) -1, true);
    }

    public static Iterator<GATKSAMRecord> applyDecoratingIterators(boolean enableVerification,
            boolean useOriginalBaseQualities,
            Iterator<GATKSAMRecord> wrappedIterator,
            Boolean noValidationOfReadOrder,
            Collection<ReadFilter> supplementalFilters,
            List<ReadTransformer> readTransformers,
            byte defaultBaseQualities,
            boolean isLocusBasedTraversal) {
        // Always apply the ReadFormattingIterator before both ReadFilters and ReadTransformers. At a minimum,
        // this will consolidate the cigar strings into canonical form. This has to be done before the read
        // filtering, because not all read filters will behave correctly with things like zero-length cigar
        // elements. If useOriginalBaseQualities is true or defaultBaseQualities >= 0, this iterator will also
        // modify the base qualities.
        wrappedIterator = new ReadFormattingIterator(wrappedIterator, useOriginalBaseQualities, defaultBaseQualities);

        wrappedIterator = new CountingFilteringIterator(wrappedIterator, supplementalFilters);

        // For locus traversals where we're downsampling to coverage by sample, assume that the downsamplers
        // will be invoked downstream from us in LocusIteratorByState. This improves performance by avoiding
        // splitting/re-assembly of the read stream at this stage, and also allows for partial downsampling
        // of individual reads.
        // TODO wu:这里hc中暂时用不上
        /*boolean assumeDownstreamLIBSDownsampling = isLocusBasedTraversal &&
                readProperties.getDownsamplingMethod().type == DownsampleType.BY_SAMPLE &&
                readProperties.getDownsamplingMethod().toCoverage != null;
        if ( ! assumeDownstreamLIBSDownsampling ) {
            wrappedIterator = applyDownsamplingIterator(wrappedIterator);
        }
        // unless they've said not to validate read ordering (!noValidationOfReadOrder) and we've enabled verification,
        // verify the read ordering by applying a sort order iterator
        if (!noValidationOfReadOrder && enableVerification)
            wrappedIterator = new VerifyingSamIterator(wrappedIterator);
         */
        // Read transformers: these are applied last, so that we don't bother transforming reads that get discarded
        // by the read filters or downsampler.
        for (final ReadTransformer readTransformer : readTransformers) {
            if (readTransformer.enabled() && readTransformer.getApplicationTime() == ReadTransformer.ApplicationTime.ON_INPUT) {
                wrappedIterator = new ReadTransformingIterator(wrappedIterator, readTransformer);
            }
        }

        return wrappedIterator;
    }

    public static Set<String> getHeader(String filename, Configuration conf) throws Exception {
        // 读取Header
        final org.apache.hadoop.fs.Path file = new org.apache.hadoop.fs.Path(filename);
        final FileSystem fs = file.getFileSystem(conf);

        try (FSDataInputStream in = fs.open(file)) {
            final SAMFileHeader header = SAMHeaderReader.readSAMHeaderFrom(in, conf);
            return ReadUtils.getSAMFileSamples(header);
        }
    }

    public static void setEnviVariable(GlobalArgument globalArgument) {
        System.setProperty(Constants.USE_OBJ_POOL, globalArgument.OBJECT_POOL.toString());
        // 如果类型是Mysql，设置环境变量
        if (globalArgument.DBSNP_DB.equals(Constants.STR_MYSQL)) {
            System.setProperty(Constants.MYSQL_URL, globalArgument.MYSQL_URL);
            System.setProperty(Constants.MYSQL_USERNAME, globalArgument.MYSQL_USERNAME);
            System.setProperty(Constants.MYSQL_PASSWORD, globalArgument.MYSQL_PASSWORD);
        } else if (globalArgument.DBSNP_DB.equals(Constants.STR_IMPALA)) {
            System.setProperty(Constants.IMPALA_URL, ImpalaVCFCodec.CONNECTION_URL);
        }
    }

}
