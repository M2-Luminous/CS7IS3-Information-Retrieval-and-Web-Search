package tcd.ie.luom;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.search.similarities.*;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.Directory;
import java.io.*;
import java.nio.file.Paths;
import java.util.Scanner;

public class App {

    private static final String INDEX_DIR = "lucene_index";
    private static final String CRAN_DATA = "/opt/cran_files/cran.all.1400";
    private static final String CRAN_QRY = "/opt/cran_files/cran.qry";
    private static final String OUTPUT_DIR = "output/output-";
    private static final String[] SCORING_TYPE_NAMES = {"BM25", "Classic", "Boolean", "LMDirichlet", "IBS"};

    public static void main(String[] args) throws Exception {
        Analyzer analyzer = setAnalyzer();
        int simType = indexer(analyzer);
        search(simType, analyzer);
        System.out.println("PROGRAM TERMINATED");
    }

    private static Analyzer setAnalyzer() {
        System.out.println("Please select the type of Analyzer:\n1. Standard Analyzer\n2. English Analyzer");
        int choice = new Scanner(System.in).nextInt();
        return choice == 1 ? new StandardAnalyzer() : new EnglishAnalyzer();
    }

    public static int indexer(Analyzer analyzer) throws IOException {
        Directory dir = FSDirectory.open(Paths.get(INDEX_DIR));
        IndexWriterConfig cfg = configureIndexWriter(analyzer);
        int simType = setSimilarity(cfg);
        IndexWriter indexWriter = new IndexWriter(dir, cfg);

        try {
            System.out.println("Indexing...");
            BufferedReader crReader = new BufferedReader(new FileReader(CRAN_DATA));
            String line = crReader.readLine();
            int docNumbers = 0;

            while (line != null) {
                Document doc = new Document();
                if (line.startsWith(".I")) {
                    String idValue = line.substring(3).trim();
                    doc.add(new StringField("id", idValue, Field.Store.YES));
                    line = crReader.readLine();
                    String crAtr = "";

                    while (line != null) {
                        if (line.matches("\\.[TABW].*")) {
                            crAtr = line.substring(0, 2);
                            line = crReader.readLine();
                        } else if (line.startsWith(".I")) {
                            break;
                        }

                        String content = crAtr.equals(".T") ? "title" :
                                crAtr.equals(".A") ? "author" :
                                        crAtr.equals(".B") ? "bib" : "content";
                        doc.add(new TextField(content, line.trim(), Field.Store.YES));
                        line = crReader.readLine();
                    }
                    indexWriter.addDocument(doc);
                }
                docNumbers++;
            }
            crReader.close();
            System.out.println("Indexing Finished, total docs: " + docNumbers);

        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        indexWriter.close();
        dir.close();
        return simType;
    }

    private static IndexWriterConfig configureIndexWriter(Analyzer analyzer) {
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
        return config;
    }

    public static int setSimilarity(IndexWriterConfig config) {
        Scanner scanner = new Scanner(System.in);
        System.out.println("Please select the type of Similarity:\n1. BM25\n2. Classic (VSM)\n3. Boolean\n4. LMDirichlet\n5. IBS");
        int simType = scanner.nextInt();

        switch (simType) {
            case 1:
                config.setSimilarity(new BM25Similarity(1.2f, 0.75f));
                System.out.println("Selected BM25");
                break;
            case 2:
                config.setSimilarity(new ClassicSimilarity());
                System.out.println("Selected Classic (VSM)");
                break;
            case 3:
                config.setSimilarity(new BooleanSimilarity());
                System.out.println("Selected Boolean");
                break;
            case 4:
                config.setSimilarity(new LMDirichletSimilarity());
                System.out.println("Selected LMDirichlet");
                break;
            case 5:
                config.setSimilarity(new IBSimilarity(new DistributionLL(), new LambdaDF(), new NormalizationH1()));
                System.out.println("Selected IBS");
                break;
            default:
                config.setSimilarity(new BM25Similarity());
                System.out.println("Selected Default (BM25)");
                simType = 1;
                break;
        }
        return simType;
    }

    public static void search(int simType, Analyzer analyzer) throws Exception {
        try (DirectoryReader reader = DirectoryReader.open(FSDirectory.open(Paths.get(INDEX_DIR)))) {
            IndexSearcher searcher = new IndexSearcher(reader);
            searcher.setSimilarity(setSearcherSimilarity(simType));

            MultiFieldQueryParser queryParser = new MultiFieldQueryParser(new String[]{"title", "author", "bib", "content"}, analyzer);

            System.out.println("Querying...");
            try (BufferedReader queryReader = new BufferedReader(new FileReader(CRAN_QRY));
                 BufferedWriter queryWriter = new BufferedWriter(new FileWriter(OUTPUT_DIR + simType + ".txt"))) {

                String queryStr;
                int queryNumber = 0;

                while ((queryStr = extractNextQuery(queryReader)) != null && !queryStr.isEmpty()) {
                    queryNumber++;
                    Query query = queryParser.parse(QueryParser.escape(queryStr));
                    ScoreDoc[] hits = searcher.search(query, 50).scoreDocs;
                    writeSearchResult(queryWriter, queryNumber, hits, searcher);
                }

                System.out.println("Querying finished, total queries: " + queryNumber);
            }
        }
    }

    public static Similarity setSearcherSimilarity(int simType) {
        switch (simType) {
            case 1:
                return new BM25Similarity();
            case 2:
                return new ClassicSimilarity();
            case 3:
                return new BooleanSimilarity();
            case 4:
                return new LMDirichletSimilarity();
            case 5:
                return new IBSimilarity(new DistributionLL(), new LambdaDF(), new NormalizationH1());
            default:
                return new BM25Similarity();
        }
    }

    public static String extractNextQuery(BufferedReader reader) throws IOException {
        String line;
        StringBuilder query = new StringBuilder();

        while ((line = reader.readLine()) != null) {
            if (line.startsWith(".W")) {
                line = reader.readLine();
                while (line != null && !line.startsWith(".I")) {
                    if (!line.startsWith(" ")) {
                        line = " " + line;
                    }
                    query.append(line);
                    line = reader.readLine();
                }
                return query.toString().trim().replace("?", "");
            }
        }
        return null;
    }

    public static void writeSearchResult(BufferedWriter writer, int queryNumber, ScoreDoc[] hits, IndexSearcher searcher) throws IOException {
        for (int i = 0; i < hits.length; i++) {
            writer.write(queryNumber + " Q0 " + searcher.doc(hits[i].doc).get("id") + " " + i +
                    " " + hits[i].score + " STANDARD");
            writer.newLine();
        }
    }
}
