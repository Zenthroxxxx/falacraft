package br.falacraft.client;

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

	private static final String URL_MODELO = "https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip";

	private static volatile boolean ativo = false;
	private static volatile boolean pausado = false;
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

				TargetDataLine linha = (TargetDataLine) AudioSystem.getLine(info);
				linha.open(formato);
				linha.start();
				aviso.accept("Microfone ligado! Fale o nome de um bloco ou mob.");

				byte[] buf = new byte[4096];
				while (ativo) {
					int n = linha.read(buf, 0, buf.length);
					if (n <= 0 || pausado) continue;
					if (rec.acceptWaveForm(buf, n)) {
						String texto = JsonParser.parseString(rec.getResult())
								.getAsJsonObject().get("text").getAsString();
						if (!texto.isBlank()) aoOuvir.accept(texto);
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
