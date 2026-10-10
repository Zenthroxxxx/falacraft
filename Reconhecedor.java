package br.falacraft.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import javax.sound.sampled.*;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Captura o microfone e reconhece fala em português (offline, com Vosk) numa thread separada. */
public final class Reconhecedor {

	// ====== AJUSTE AQUI ======
	/** Volume mínimo para considerar que alguém está falando (0 a 32768). Se pegar ruído, AUMENTE. Se não pegar sua voz, DIMINUA. */
	static final int LIMIAR_VOLUME = 600;
	/** Confiança mínima do reconhecimento (0.0 a 1.0). Se ignorar muita coisa que você falou, DIMINUA. */
	static final double CONFIANCA_MINIMA = 0.6;
	/** Quantos pedaços de áudio silenciosos (~0,13 s cada) encerram uma frase. */
	static final int BUFFERS_DE_SILENCIO = 4;
	// =========================

	private static final String URL_MODELO = "https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip";

	private static volatile boolean ativo = false;
	private static volatile boolean pausado = false;
	/** Volume mais alto da última frase ouvida (ajuda a calibrar o LIMIAR_VOLUME). */
	public static volatile int ultimoVolume = 0;
	private static Thread thread;

	private Reconhecedor() { }

	public static synchronized void iniciar(Consumer<String> aoOuvir, Consumer<String> aviso) {
		if (thread != null && thread.isAlive()) return;
		ativo = true;
		thread = new Thread(() -> rodar(aoOuvir, aviso), "FalaCraft-Voz");
		thread.setDaemon(true);
		thread.start();
	}

	public static void parar() {
		ativo = false;
	}

	/** @return true se agora está pausado (microfone desligado) */
	public static boolean alternarPausa() {
		pausado = !pausado;
		return pausado;
	}

	private static void rodar(Consumer<String> aoOuvir, Consumer<String> aviso) {
		try {
			Path pasta = FabricLoader.getInstance().getConfigDir().resolve("falacraft");
			Path modelo = pasta.resolve("modelo");
			if (!Files.isDirectory(modelo.resolve("am"))) {
				aviso.accept("Baixando modelo de voz em português (~31 MB), só na primeira vez...");
				baixarModelo(modelo);
				aviso.accept("Modelo baixado!");
			}

			LibVosk.setLogLevel(LogLevel.WARNINGS);
			AudioFormat formato = new AudioFormat(16000f, 16, 1, true, false);
			DataLine.Info info = new DataLine.Info(TargetDataLine.class, formato);
			if (!AudioSystem.isLineSupported(info)) {
				aviso.accept("Nenhum microfone encontrado.");
				return;
			}

			try (Model m = new Model(modelo.toString());
				 Recognizer rec = new Recognizer(m, 16000f)) {
				rec.setWords(true);

				TargetDataLine linha = (TargetDataLine) AudioSystem.getLine(info);
				linha.open(formato);
				linha.start();
				aviso.accept("Microfone ligado! Fale o nome de um bloco ou mob.");

				byte[] buf = new byte[4096];
				byte[] anterior = new byte[4096];
				int anteriorN = 0;
				boolean falando = false;
				int silencio = 0;
				int volumeMax = 0;

				while (ativo) {
					int n = linha.read(buf, 0, buf.length);
					if (n <= 0) continue;
					if (pausado) { falando = false; anteriorN = 0; continue; }

					int vol = volume(buf, n);
					boolean alto = vol >= LIMIAR_VOLUME;

					if (!falando) {
						if (!alto) { // silêncio/ruído baixo: ignora e guarda só o último pedaço
							System.arraycopy(buf, 0, anterior, 0, n);
							anteriorN = n;
							continue;
						}
						falando = true;
						silencio = 0;
						volumeMax = 0;
						if (anteriorN > 0) rec.acceptWaveForm(anterior, anteriorN); // não perde o começo da palavra
					}

					volumeMax = Math.max(volumeMax, vol);
					silencio = alto ? 0 : silencio + 1;

					boolean fim = rec.acceptWaveForm(buf, n);
					String json = null;
					if (fim) {
						json = rec.getResult();
					} else if (silencio >= BUFFERS_DE_SILENCIO) {
						json = rec.getFinalResult();
						rec.reset();
						fim = true;
					}

					if (fim) {
						falando = false;
						silencio = 0;
						anteriorN = 0;
						ultimoVolume = volumeMax;
						tratar(json, aoOuvir);
					}
				}
				linha.stop();
				linha.close();
			}
		} catch (Throwable e) {
			e.printStackTrace();
			aviso.accept("Erro no microfone/voz: " + e.getMessage());
		}
	}

	private static void tratar(String json, Consumer<String> aoOuvir) {
		if (json == null) return;
		JsonObject o = JsonParser.parseString(json).getAsJsonObject();
		if (!o.has("text")) return;
		String texto = o.get("text").getAsString();
		if (texto.isBlank()) return;

		// descarta palavras que o modelo "chutou" com pouca confiança
		if (o.has("result")) {
			double soma = 0;
			int c = 0;
			for (JsonElement e : o.getAsJsonArray("result")) {
				soma += e.getAsJsonObject().get("conf").getAsDouble();
				c++;
			}
			if (c > 0 && soma / c < CONFIANCA_MINIMA) return;
		}
		aoOuvir.accept(texto);
	}

	/** Volume (RMS) de um pedaço de áudio 16-bit little-endian. */
	private static int volume(byte[] buf, int n) {
		long soma = 0;
		int amostras = n / 2;
		for (int i = 0; i + 1 < n; i += 2) {
			short s = (short) ((buf[i + 1] << 8) | (buf[i] & 0xff));
			soma += (long) s * s;
		}
		return (int) Math.sqrt(soma / (double) Math.max(1, amostras));
	}

	private static void baixarModelo(Path destino) throws Exception {
		Files.createDirectories(destino);
		Path zip = destino.resolveSibling("modelo.zip");

		HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
		HttpRequest req = HttpRequest.newBuilder(URI.create(URL_MODELO)).build();
		http.send(req, HttpResponse.BodyHandlers.ofFile(zip));

		try (InputStream in = Files.newInputStream(zip); ZipInputStream zis = new ZipInputStream(in)) {
			ZipEntry e;
			while ((e = zis.getNextEntry()) != null) {
				String nome = e.getName();
				int barra = nome.indexOf('/');          // tira a pasta raiz do zip
				if (barra < 0) continue;
				String rel = nome.substring(barra + 1);
				if (rel.isEmpty()) continue;
				Path alvo = destino.resolve(rel).normalize();
				if (!alvo.startsWith(destino)) continue; // proteção contra zip-slip
				if (e.isDirectory()) {
					Files.createDirectories(alvo);
				} else {
					Files.createDirectories(alvo.getParent());
					Files.copy(zis, alvo, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
		Files.deleteIfExists(zip);
	}
}
