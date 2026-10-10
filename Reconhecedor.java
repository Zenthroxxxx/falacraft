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
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Captura o microfone e reconhece fala em português (offline, com Vosk) numa thread separada. */
public final class Reconhecedor {

	/** Uma frase reconhecida e a confiança média do modelo (0.0 a 1.0). */
	public record Fala(String texto, double confianca) { }

	// ====== AJUSTE AQUI ======
	/** Volume mínimo absoluto para considerar fala. O mod também se adapta sozinho ao ruído do ambiente. */
	static final int LIMIAR_MINIMO = 150;
	/** Fala precisa ser X vezes mais alta que o ruído de fundo. */
	static final double MULT_RUIDO = 3.0;
	/** Quantos pedaços de áudio silenciosos (~0,13 s cada) encerram uma frase. */
	static final int BUFFERS_DE_SILENCIO = 4;
	/** Volume para o qual o mod amplifica sua voz automaticamente (ajuda microfones baixos). */
	static final int VOLUME_ALVO = 2500;
	// =========================

	private static final String URL_MODELO = "https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip";
	private static final AudioFormat FORMATO = new AudioFormat(16000f, 16, 1, true, false);
	private static final DataLine.Info INFO = new DataLine.Info(TargetDataLine.class, FORMATO);

	private static volatile boolean ativo = false;
	private static volatile boolean pausado = false;
	private static volatile boolean reabrir = false;
	private static volatile String microfoneEscolhido = null; // null = padrão do sistema

	/** Valores ao vivo para o medidor na tela. */
	public static volatile int volumeAtual = 0;
	public static volatile int limiarAtual = 0;
	/** Volume mais alto da última frase ouvida. */
	public static volatile int ultimoVolume = 0;

	private static Thread thread;

	private Reconhecedor() { }

	// ---------- controle ----------

	public static synchronized void iniciar(Consumer<Fala> aoOuvir, Consumer<String> aviso) {
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

	public static boolean estaPausado() {
		return pausado;
	}

	// ---------- escolha de microfone ----------

	public static String nomeMicrofone() {
		return microfoneEscolhido == null ? "Padrão do sistema" : microfoneEscolhido;
	}

	/** Lista os dispositivos de entrada disponíveis (o primeiro é sempre o padrão do sistema). */
	public static List<String> opcoes() {
		List<String> l = new ArrayList<>();
		l.add("Padrão do sistema");
		for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
			try {
				if (AudioSystem.getMixer(mi).isLineSupported(INFO)) l.add(mi.getName());
			} catch (Exception ignorado) { }
		}
		return l;
	}

	/** Passa para o próximo microfone da lista e reabre a captura. */
	public static String trocarMicrofone() {
		List<String> l = opcoes();
		int i = microfoneEscolhido == null ? 0 : Math.max(0, l.indexOf(microfoneEscolhido));
		int prox = (i + 1) % l.size();
		microfoneEscolhido = prox == 0 ? null : l.get(prox);
		salvarConfig();
		reabrir = true;
		return nomeMicrofone();
	}

	private static Path arquivoConfig() {
		return FabricLoader.getInstance().getConfigDir().resolve("falacraft").resolve("config.properties");
	}

	private static void carregarConfig() {
		try {
			Path p = arquivoConfig();
			if (!Files.exists(p)) return;
			Properties props = new Properties();
			try (InputStream in = Files.newInputStream(p)) {
				props.load(in);
			}
			String nome = props.getProperty("microfone");
			microfoneEscolhido = (nome == null || nome.isBlank()) ? null : nome;
		} catch (Exception ignorado) { }
	}

	private static void salvarConfig() {
		try {
			Path p = arquivoConfig();
			Files.createDirectories(p.getParent());
			Properties props = new Properties();
			props.setProperty("microfone", microfoneEscolhido == null ? "" : microfoneEscolhido);
			try (OutputStream out = Files.newOutputStream(p)) {
				props.store(out, "FalaCraft");
			}
		} catch (Exception ignorado) { }
	}

	private static TargetDataLine abrirLinha() throws Exception {
		TargetDataLine linha = null;
		String nome = microfoneEscolhido;
		if (nome != null) {
			for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
				if (mi.getName().equals(nome) && AudioSystem.getMixer(mi).isLineSupported(INFO)) {
					linha = (TargetDataLine) AudioSystem.getMixer(mi).getLine(INFO);
					break;
				}
			}
		}
		if (linha == null) linha = (TargetDataLine) AudioSystem.getLine(INFO);
		linha.open(FORMATO);
		linha.start();
		return linha;
	}

	// ---------- thread principal ----------

	private static void rodar(Consumer<Fala> aoOuvir, Consumer<String> aviso) {
		try {
			carregarConfig();

			Path pasta = FabricLoader.getInstance().getConfigDir().resolve("falacraft");
			Path modelo = pasta.resolve("modelo");
			if (!Files.isDirectory(modelo.resolve("am"))) {
				aviso.accept("Baixando modelo de voz em português (~31 MB), só na primeira vez...");
				baixarModelo(modelo);
				aviso.accept("Modelo baixado!");
			}

			LibVosk.setLogLevel(LogLevel.WARNINGS);
			try (Model m = new Model(modelo.toString());
				 Recognizer rec = new Recognizer(m, 16000f)) {
				rec.setWords(true);

				while (ativo) {
					reabrir = false;
					TargetDataLine linha;
					try {
						linha = abrirLinha();
					} catch (Exception e) {
						aviso.accept("Não consegui abrir o microfone \"" + nomeMicrofone() + "\". Aperte B para trocar.");
						while (ativo && !reabrir) Thread.sleep(200);
						continue;
					}
					aviso.accept("Microfone: " + nomeMicrofone() + "  (B troca, N mostra/esconde o medidor)");
					try {
						capturar(linha, rec, aoOuvir);
					} finally {
						linha.stop();
						linha.close();
					}
				}
			}
		} catch (Throwable e) {
			e.printStackTrace();
			aviso.accept("Erro no microfone/voz: " + e.getMessage());
		}
	}

	private static void capturar(TargetDataLine linha, Recognizer rec, Consumer<Fala> aoOuvir) {
		byte[] buf = new byte[4096];
		byte[] anterior = new byte[4096];
		byte[] saida = new byte[4096];
		int anteriorN = 0;
		boolean falando = false;
		int silencio = 0;
		int volumeMax = 0;
		double ruido = 100;
		double nivelFala = 1500;
		rec.reset();

		while (ativo && !reabrir) {
			int n = linha.read(buf, 0, buf.length);
			if (n <= 0) continue;

			int vol = volume(buf, n);
			volumeAtual = vol;
			if (pausado) { falando = false; anteriorN = 0; continue; }

			int limiar = (int) Math.max(LIMIAR_MINIMO, ruido * MULT_RUIDO);
			limiarAtual = limiar;
			boolean alto = vol >= limiar;
			if (!alto) ruido = 0.98 * ruido + 0.02 * vol; // aprende o ruído de fundo

			if (!falando) {
				if (!alto) { // silêncio/ruído: guarda só o último pedaço
					System.arraycopy(buf, 0, anterior, 0, n);
					anteriorN = n;
					continue;
				}
				falando = true;
				silencio = 0;
				volumeMax = 0;
				if (anteriorN > 0) { // não perde o começo da palavra
					rec.acceptWaveForm(amplificar(anterior, anteriorN, ganho(nivelFala), saida), anteriorN);
				}
			}

			if (alto) nivelFala = 0.8 * nivelFala + 0.2 * vol;
			volumeMax = Math.max(volumeMax, vol);
			silencio = alto ? 0 : silencio + 1;

			boolean fim = rec.acceptWaveForm(amplificar(buf, n, ganho(nivelFala), saida), n);
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
	}

	private static void tratar(String json, Consumer<Fala> aoOuvir) {
		if (json == null) return;
		JsonObject o = JsonParser.parseString(json).getAsJsonObject();
		if (!o.has("text")) return;
		String texto = o.get("text").getAsString();
		if (texto.isBlank()) return;

		double conf = 1.0;
		if (o.has("result")) {
			double soma = 0;
			int c = 0;
			for (JsonElement e : o.getAsJsonArray("result")) {
				soma += e.getAsJsonObject().get("conf").getAsDouble();
				c++;
			}
			if (c > 0) conf = soma / c;
		}
		aoOuvir.accept(new Fala(texto, conf));
	}

	// ---------- áudio ----------

	private static double ganho(double nivelFala) {
		return Math.min(10.0, Math.max(1.0, VOLUME_ALVO / Math.max(nivelFala, 1.0)));
	}

	private static byte[] amplificar(byte[] src, int n, double g, byte[] dst) {
		for (int i = 0; i + 1 < n; i += 2) {
			int s = (short) ((src[i + 1] << 8) | (src[i] & 0xff));
			s = (int) Math.max(-32768, Math.min(32767, s * g));
			dst[i] = (byte) (s & 0xff);
			dst[i + 1] = (byte) ((s >> 8) & 0xff);
		}
		return dst;
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

	// ---------- modelo ----------

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
