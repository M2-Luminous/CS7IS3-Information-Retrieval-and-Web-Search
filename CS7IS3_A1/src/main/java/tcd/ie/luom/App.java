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

    private static final String INDEX_DIRECTORY = "lucene_index";
    private static final String CRAN_DOCUMENTS = "/opt/cran_files/cran.all.1400";
    private static final String CRAN_QUERIES = "/opt/cran_files/cran.qry";
    private static String OUTPUT_FILE_PATH = "output/output-";

    public static void main(String[] args) throws Exception {
        String selectedAnalyzer = chooseAnalyzer();
        int similarityModel = indexDocuments(selectedAnalyzer);
        executeSearch(similarityModel, selectedAnalyzer);
        System.out.println("PROGRAM TERMINATED");
    }

    private static String chooseAnalyzer() {
        System.out.println("Please select the type of Analyzer:\n1. Standard Analyzer\n2. English Analyzer");
        int userChoice = new Scanner(System.in).nextInt();
        return userChoice == 1 ? "sd" : "en";
    }

    public static int indexDocuments(String selectedAnalyzer) throws IOException {
        Directory indexDirectory = FSDirectory.open(Paths.get(INDEX_DIRECTORY));
        Analyzer analyzer = selectedAnalyzer.equals("sd") ? new StandardAnalyzer() : new EnglishAnalyzer();
        IndexWriterConfig writerConfig = createIndexWriterConfig(analyzer);
        int similarityModel = chooseSimilarityModel();

        OUTPUT_FILE_PATH += selectedAnalyzer + "-" + getSimilarityModelName(similarityModel) + ".txt";

        writerConfig.setSimilarity(getSimilarityModel(similarityModel));
        IndexWriter indexWriter = new IndexWriter(indexDirectory, writerConfig);

        try {
            System.out.println("Indexing documents...");
            BufferedReader documentReader = new BufferedReader(new FileReader(CRAN_DOCUMENTS));
            String currentLine = documentReader.readLine();
            int documentCount = 0;
            Document currentDocument = null;
            String currentField = null;

            while (currentLine != null) {
                if (currentLine.startsWith(".I")) {
                    if (currentDocument != null) {
                        indexWriter.addDocument(currentDocument);
                        documentCount++;
                    }
                    currentDocument = new Document();
                    String documentId = currentLine.substring(3).trim();
                    currentDocument.add(new StringField("id", documentId, Field.Store.YES));
                } else if (currentLine.startsWith(".T")) {
                    currentField = "title";
                } else if (currentLine.startsWith(".A")) {
                    currentField = "author";
                } else if (currentLine.startsWith(".B")) {
                    currentField = "bibliography";
                } else if (currentLine.startsWith(".W")) {
                    currentField = "content";
                } else if (currentField != null) {
                    currentDocument.add(new TextField(currentField, currentLine.trim(), Field.Store.YES));
                }
                currentLine = documentReader.readLine();
            }

            if (currentDocument != null) {
                indexWriter.addDocument(currentDocument);
                documentCount++;
            }

            documentReader.close();
            System.out.println("Indexing completed, total documents indexed: " + documentCount);

        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        indexWriter.close();
        indexDirectory.close();
        return similarityModel;
    }

    private static IndexWriterConfig createIndexWriterConfig(Analyzer analyzer) {
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
        return config;
    }

    public static int chooseSimilarityModel() {
        Scanner scanner = new Scanner(System.in);
        System.out.println("Please select the type of Similarity Model:\n1. BM25\n2. Classic (VSM)\n3. Boolean\n4. LMDirichlet\n5. IBS");
        return scanner.nextInt();
    }

    public static Similarity getSimilarityModel(int similarityModel) {
        switch (similarityModel) {
            case 1:
                return new BM25Similarity(1.2f, 0.75f);
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

    public static void executeSearch(int similarityModel, String selectedAnalyzer) throws Exception {
        try (DirectoryReader indexReader = DirectoryReader.open(FSDirectory.open(Paths.get(INDEX_DIRECTORY)))) {
            IndexSearcher searcher = new IndexSearcher(indexReader);
            searcher.setSimilarity(getSimilarityModel(similarityModel));

            Analyzer analyzer = selectedAnalyzer.equals("sd") ? new StandardAnalyzer() : new EnglishAnalyzer();

            HashMap<String, Float> fieldBoosts = new HashMap<>();
            fieldBoosts.put("title", 0.65f);
            fieldBoosts.put("author", 0.04f);
            fieldBoosts.put("bibliography", 0.02f);
            fieldBoosts.put("content", 0.35f);

            MultiFieldQueryParser queryParser = new MultiFieldQueryParser(new String[]{"title", "author", "bibliography", "content"}, analyzer); //,fieldBoosts

            System.out.println("Executing search queries...");
            try (BufferedReader queryFileReader = new BufferedReader(new FileReader(CRAN_QUERIES));
                 BufferedWriter queryResultWriter = new BufferedWriter(new FileWriter(OUTPUT_FILE_PATH))) {

                String currentQuery;
                int queryNumber = 0;

                while ((currentQuery = extractNextQuery(queryFileReader)) != null && !currentQuery.isEmpty()) {
                    queryNumber++;
                    Query parsedQuery = queryParser.parse(QueryParser.escape(currentQuery));
                    ScoreDoc[] searchResults = searcher.search(parsedQuery, 50).scoreDocs;
                    for (int i = 0; i < searchResults.length; i++) {
                        queryResultWriter.write(queryNumber + " Q0 " + searcher.doc(searchResults[i].doc).get("id") + " " + i + " " + searchResults[i].score + " STANDARD");
                        queryResultWriter.newLine();
                    }
                }

                System.out.println("Search queries completed, total queries executed: " + queryNumber);
            }
        }
    }

    public static String extractNextQuery(BufferedReader queryReader) throws IOException {
        StringBuilder queryText = new StringBuilder();
        String line;

        while ((line = queryReader.readLine()) != null) {
            if (line.startsWith(".W")) {
                while ((line = queryReader.readLine()) != null && !line.startsWith(".I")) {
                    queryText.append(line.trim()).append(" ");
                }
                return queryText.toString().trim().replace("?", "");
            }
        }
        return null;
    }

    public static String getSimilarityModelName(int similarityModel) {
        switch (similarityModel) {
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
