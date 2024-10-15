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
import java.util.HashMap;

public class App {

    private static final String INDEX_DIR = "lucene_index";
    private static final String CRAN_DATA = "/opt/cran_files/cran.all.1400";
    private static final String CRAN_QRY = "/opt/cran_files/cran.qry";
    private static String OUTPUT_DIR = "output/output-";

    public static void main(String[] args) throws Exception {
        String analyzerName = setAnalyzer();
        int simType = indexer(analyzerName);
        search(simType, analyzerName);
        System.out.println("PROGRAM TERMINATED");
    }

    private static String setAnalyzer() {
        System.out.println("Please select the type of Analyzer:\n1. Standard Analyzer\n2. English Analyzer");
        int choice = new Scanner(System.in).nextInt();
        return choice == 1 ? "sd" : "en";
    }

    public static int indexer(String analyzerName) throws IOException {
        Directory dir = FSDirectory.open(Paths.get(INDEX_DIR));
        Analyzer analyzer = analyzerName.equals("sd") ? new StandardAnalyzer() : new EnglishAnalyzer();
        IndexWriterConfig cfg = configureIndexWriter(analyzer);
        int simType = selectSimilarityType();

        OUTPUT_DIR += analyzerName + "-" + getSimilarityName(simType) + ".txt";

        cfg.setSimilarity(getSimilarity(simType));
        IndexWriter indexWriter = new IndexWriter(dir, cfg);

        try {
            System.out.println("Indexing...");
            BufferedReader crReader = new BufferedReader(new FileReader(CRAN_DATA));
            String line = crReader.readLine();
            int docNumbers = 0;
            Document doc = null;
            String contentField = null;

            while (line != null) {
                if (line.startsWith(".I")) {
                    if (doc != null) {
                        indexWriter.addDocument(doc);
                        docNumbers++;
                    }
                    doc = new Document();
                    String idValue = line.substring(3).trim();
                    doc.add(new StringField("id", idValue, Field.Store.YES));
                } else if (line.startsWith(".T")) {
                    contentField = "title";
                } else if (line.startsWith(".A")) {
                    contentField = "author";
                } else if (line.startsWith(".B")) {
                    contentField = "bib";
                } else if (line.startsWith(".W")) {
                    contentField = "content";
                } else if (contentField != null) {
                    doc.add(new TextField(contentField, line.trim(), Field.Store.YES));
                }
                line = crReader.readLine();
            }

            if (doc != null) {
                indexWriter.addDocument(doc);
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

    public static int selectSimilarityType() {
        Scanner scanner = new Scanner(System.in);
        System.out.println("Please select the type of Similarity:\n1. BM25\n2. Classic (VSM)\n3. Boolean\n4. LMDirichlet\n5. IBS");
        return scanner.nextInt();
    }

    public static Similarity getSimilarity(int simType) {
        switch (simType) {
            case 1:
                System.out.println("Selected BM25");
                return new BM25Similarity(1.2f, 0.75f);
            case 2:
                System.out.println("Selected Classic (VSM)");
                return new ClassicSimilarity();
            case 3:
                System.out.println("Selected Boolean");
                return new BooleanSimilarity();
            case 4:
                System.out.println("Selected LMDirichlet");
                return new LMDirichletSimilarity();
            case 5:
                System.out.println("Selected IBS");
                return new IBSimilarity(new DistributionLL(), new LambdaDF(), new NormalizationH1());
            default:
                System.out.println("Selected Default (BM25)");
                return new BM25Similarity();
        }
    }

    public static void search(int simType, String analyzerName) throws Exception {
        try (DirectoryReader reader = DirectoryReader.open(FSDirectory.open(Paths.get(INDEX_DIR)))) {
            IndexSearcher searcher = new IndexSearcher(reader);
            searcher.setSimilarity(getSimilarity(simType));

            Analyzer analyzer = analyzerName.equals("sd") ? new StandardAnalyzer() : new EnglishAnalyzer();

	    HashMap<String, Float> boostedScores = new HashMap<>();
    	    boostedScores.put("title", 0.65f);
    	    boostedScores.put("author", 0.04f);
    	    boostedScores.put("bib", 0.02f);
     	    boostedScores.put("content", 0.35f);

            MultiFieldQueryParser queryParser = new MultiFieldQueryParser(new String[]{"title", "author", "bib", "content"}, analyzer, boostedScores);

            System.out.println("Querying...");
            try (BufferedReader queryReader = new BufferedReader(new FileReader(CRAN_QRY));
                 BufferedWriter queryWriter = new BufferedWriter(new FileWriter(OUTPUT_DIR))) {

                String queryStr;
                int queryNumber = 0;

                while ((queryStr = extractNextQuery(queryReader)) != null && !queryStr.isEmpty()) {
                    queryNumber++;
                    Query query = queryParser.parse(QueryParser.escape(queryStr));
                    ScoreDoc[] hits = searcher.search(query, 50).scoreDocs;
		    for (int i = 0; i < hits.length; i++) {
           	        queryWriter.write(queryNumber + " Q0 " + searcher.doc(hits[i].doc).get("id") + " " + i + " " + hits[i].score + " STANDARD");
                    queryWriter.newLine();
        }
                }

                System.out.println("Querying finished, total queries: " + queryNumber);
            }
        }
    }

    public static String extractNextQuery(BufferedReader reader) throws IOException {
        StringBuilder query = new StringBuilder();
        String line;

        while ((line = reader.readLine()) != null) {
            if (line.startsWith(".W")) {
                while ((line = reader.readLine()) != null && !line.startsWith(".I")) {
                    query.append(line.trim()).append(" ");
                }
                return query.toString().trim().replace("?", "");
            }
        }
        return null;
    }

    public static String getSimilarityName(int simType) {
        switch (simType) {
            case 1:
                return "bm25";
            case 2:
                return "classic";
            case 3:
                return "boolean";
            case 4:
                return "lm";
            case 5:
                return "ibs";
            default:
                return "bm25";
        }
    }
}
