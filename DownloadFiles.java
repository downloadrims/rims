package download_RIMS;

import java.awt.Toolkit;
import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

public class DownloadFiles {
	public static final String basePath = "M:\\My_Video(8TB)\\RIMS(原本)\\";
	public static final String[] folders = new String[]{
			"0001-0499\\",
			"0500-0999\\",
			"1000-1499\\",
			"1500-1799\\",
			"1800-2152\\",
	};
	public static final String[] xmlFiles = new String[]{
			"RIMS_XML_data_(0001-0499)_2020-04-01：18-06-38.xml",
			"RIMS_XML_data_(0500-0999)_2020-04-01：16-53-11.xml",
			"RIMS_XML_data_(1000-1499)_2020-04-01：19-06-33.xml",
			"RIMS_XML_data_(1500-1799)_2020-04-03：17-26-27.xml",
			"RIMS_XML_data_(1800-2152)_2020-04-26：22-43-00.xml",
	};
	public static final int maxTitleSize = 120;

	public static final int Download_Start_Number = 1500;
	public static final int Download_End_Number = 1799;

	public static final boolean debug = true; // デバッグ用：ファイル入出力の処理をするかどうか

	public static void main(String[] args) {
		try {
			// 1つのファイル名を1行で書き出す
			File filePath = new File(basePath + "1line_output.txt");
			FileWriter filewriter = new FileWriter(filePath, false);
			PrintWriter printwriter = new PrintWriter(new BufferedWriter(filewriter));

			// 並列ダウンロード用の準備
			ExecutorService executor = Executors.newFixedThreadPool(20); // 並列20まで

			// Download_Start_Number回からDownload_End_Number回までダウンロードする
			for (int i = getIndex(Download_Start_Number); i <= getIndex(Download_End_Number); i++) {
				Document doc = Jsoup.parse(
						new BufferedInputStream(
								new FileInputStream(basePath + "RIMS_XML_data\\" + xmlFiles[i])),
						"utf-8", "", Parser.xmlParser());
				for (Element folder : doc.select("RIMS > folder")) { // 各フォルダー
					int[] order = new int[2]; // 0:開催回、1:発表者の番目
					order[0] = Integer.parseInt(folder.attr("num")); // 開催回
					if (order[0] < Download_Start_Number || Download_End_Number < order[0]) {
						continue; // 現在のフォルダーが望みの開催回でないならば、スルー
					}
					// フォルダー名作成
					Element tmp = folder.select("title > japanese").get(0);
					if (!tmp.hasText()) {
						tmp = tmp.nextElementSibling(); // 日本語の開催タイトルが無いなら、英語で代替
					}
					String folderName = String.format("[%04d：%s]", order[0], tmp.text());
					// フォルダー名出力
					printwriter.printf("%s _%s\r\n", folderName, folder.select("date").text());
					// フォルダー作成
					if (debug) {
						Files.createDirectories(Paths.get(basePath + folders[i] + folderName));
					}
					// そのフォルダー内に作成すべきファイルを1つ1つ処理
					for (Element file : folder.select("files > file")) {
						order[1] = Integer.parseInt(file.attr("num"));
						if (!file.select("link").hasText()) { // リンクが無い時はスキップ
							continue;
						}
						// ファイル名作成
						String tmpPage = ""; // ページ番号記載部分
						if (Integer.parseInt(file.attr("pageTo")) != -1) { // -1=ページ番号が不明(未定)
							tmpPage = String.format("%03d-%03d",
									Integer.parseInt(file.attr("pageFrom")),
									Integer.parseInt(file.attr("pageTo")));
						}
						String tmpTitle = file.select("title").text();
						if (tmpTitle.length() >= maxTitleSize) { // タイトル名が長すぎた時は略す
							tmpTitle = tmpTitle.substring(0, maxTitleSize) + "【以下略】";
						}
						String fileName = String.format("(%02d：%s)[%s](%s)",
								order[1], tmpPage, tmpTitle, file.select("auther").text());
						if (fileName.length() >= maxTitleSize + 15) { // まだ長すぎるなら、著者名を減らす
							fileName = String.format("(%02d：%s)[%s](%s)",
									order[1], tmpPage, tmpTitle, shortenName(file.select("auther").text()));
						}
						printwriter.print(fileName + "\r\n"); // ファイル名出力
						int c = (basePath + folders[i] + folderName + "\\" + fileName + ".pdf").length();
						if (c >= 250) { // これでもまだパス名が長すぎる時は要注意と言うことで一応チェック。
							System.out.println("large:" + c + ":" + fileName);
						}
						// ファイルを並列してダウンロード
						if (debug) { // ←デバッグ用
							executor.execute(new MyThread(
									file.select("link").text(), i, folderName, fileName));
						}
					}
					printwriter.printf("\r\n");
				}
			}
			printwriter.close();

			// シャットダウンを開始して、これ以降のタスクは受け付けない
			executor.shutdown();
			// 全タスクが完了するかタイムアウトするまでブロックする
			executor.awaitTermination(10, TimeUnit.SECONDS);
		} catch (Exception e) {
			e.printStackTrace();
		}
		// makeFinishSound(); // ダウンロード処理が終了したら音を鳴らしてお知らせ、のつもり(没)
	}

	private static int getIndex(int num) {
		if (1 <= num && num <= 499) {
			return 0;
		} else if (500 <= num && num <= 999) {
			return 1;
		} else if (1000 <= num && num <= 1499) {
			return 2;
		} else if (1500 <= num && num <= 1799) {
			return 3;
		} else if (1800 <= num && num <= 2152) {
			return 4;
		} else {
			System.out.println("エラー：不正なインデックス");
			return -1;
		}
	}

	// 著者が3人以上居る時は、3人目以降は全部省いて、"[他]"の一言で済ます。
	private static String shortenName(String namesStr) {
		String[] names = namesStr.split(",");
		if (names.length <= 2) {
			return namesStr;
		} else {
			return String.format("%s,%s,[他]", names[0], names[1]);
		}
	}

	// ダウンロード処理が終了した音声を出力する
	private static void makeFinishSound() {
		int count = 3;
		for (int i = 1; i <= count; i++) {
			Toolkit.getDefaultToolkit().beep();
			if (i < count) {
				try {
					TimeUnit.SECONDS.sleep(2);
				} catch (Exception e) {
				}
			}
		}
	}

	// 並列ダウンロードをするためのスレッド
	static class MyThread implements Runnable {
		int index;
		String link, outputPath;
		MyThread(String link, int i, String folderName, String fileName) {
			this.link = link;
			index = i;
			outputPath = basePath + folders[index] + folderName + "\\" + fileName + ".pdf";
		}

		public void run() {
			try(BufferedInputStream in = new BufferedInputStream(new URL(link).openStream());) {
				Files.copy(in, Paths.get(outputPath), StandardCopyOption.REPLACE_EXISTING);
			} catch (Exception e) {
				System.out.println("ダウンロードエラー発生:" + link);
				e.printStackTrace();
			}
		}
	}
}
