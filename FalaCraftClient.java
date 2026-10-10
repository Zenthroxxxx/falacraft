package br.falacraft.client;

import br.falacraft.PacoteVoz;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public class FalaCraftClient implements ClientModInitializer {

	/** Abaixo disso o mod só mostra o que ouviu, sem executar. Se ignorar demais, DIMINUA. */
	static final double CONFIANCA_MINIMA = 0.4;

	private static KeyMapping teclaMicrofone;  // V: liga/desliga
	private static KeyMapping teclaTrocar;     // B: troca de microfone
	private static KeyMapping teclaMedidor;    // N: mostra/esconde o medidor de volume
	private static KeyMapping teclaTeste;      // J: teste sem voz (pede 20 zumbis ao servidor)

	private static boolean mostrarMedidor = true;
	private static String ultimaMsg = "";
	private static String avisoInicial = null;
	private static int contador = 0;

	@Override
	public void onInitializeClient() {
		teclaMicrofone = tecla("key.falacraft.microfone", GLFW.GLFW_KEY_V);
		teclaTrocar = tecla("key.falacraft.trocar_microfone", GLFW.GLFW_KEY_B);
		teclaMedidor = tecla("key.falacraft.medidor", GLFW.GLFW_KEY_N);
		teclaTeste = tecla("key.falacraft.teste", GLFW.GLFW_KEY_J);

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (avisoInicial != null && mc.player != null) {
				chat(mc, avisoInicial);
				avisoInicial = null;
			}

			while (teclaMicrofone.consumeClick()) {
				boolean desligado = Reconhecedor.alternarPausa();
				mensagem(mc, desligado ? "Microfone DESLIGADO" : "Microfone LIGADO");
			}
			while (teclaTrocar.consumeClick()) {
				String nome = Reconhecedor.trocarMicrofone();
				mensagem(mc, "Microfone: " + nome);
				chat(mc, "Microfone: " + nome);
			}
			while (teclaMedidor.consumeClick()) {
				mostrarMedidor = !mostrarMedidor;
				ultimaMsg = mostrarMedidor ? "Medidor ligado" : "Medidor desligado";
				if (!mostrarMedidor) mostrarNaTela(mc, ultimaMsg);
			}
			while (teclaTeste.consumeClick()) {
				if (!ClientPlayNetworking.canSend(PacoteVoz.TIPO)) {
					chat(mc, "TESTE FALHOU: o servidor não tem o mod (cliente não consegue enviar).");
				} else {
					ClientPlayNetworking.send(new PacoteVoz("mob", "minecraft:zombie"));
					chat(mc, "TESTE: pedi zumbis ao servidor (sem usar a voz).");
				}
			}

			if (mostrarMedidor && mc.player != null && ++contador % 4 == 0) {
				String estado = Reconhecedor.estaPausado() ? "MUDO | " : "";
				mostrarNaTela(mc, estado + "Mic: " + curto(Reconhecedor.nomeMicrofone())
						+ " | vol " + Reconhecedor.volumeAtual + " (precisa " + Reconhecedor.limiarAtual + ") | " + ultimaMsg);
			}
		});

		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
			Dicionario.construir();
			Dicionario.Entrada teste = Dicionario.procurar("zumbi");
			avisoInicial = "Dicionário com " + Dicionario.tamanho() + " nomes. \"zumbi\" -> "
					+ (teste == null ? "NÃO ACHEI (o idioma do jogo está em Português (Brasil)?)" : teste.id())
					+ ". Tecla J = teste sem voz.";
			Reconhecedor.iniciar(
					fala -> mc.execute(() -> tratar(mc, fala)),
					aviso -> mc.execute(() -> mensagem(mc, aviso)));
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> Reconhecedor.parar());
	}

	private static KeyMapping tecla(String nome, int glfw) {
		return KeyMappingHelper.registerKeyMapping(
				new KeyMapping(nome, InputConstants.Type.KEYSYM, glfw, KeyMapping.Category.MISC));
	}

	private static void tratar(Minecraft mc, Reconhecedor.Fala fala) {
		if (mc.player == null) return;
		String f = Dicionario.normalizar(fala.texto());
		if (f.isBlank()) return;

		int pct = (int) Math.round(fala.confianca() * 100);
		if (fala.confianca() < CONFIANCA_MINIMA) {
			mensagem(mc, "Ouvi com pouca certeza (" + pct + "%): " + fala.texto());
			return;
		}

		if (!ClientPlayNetworking.canSend(PacoteVoz.TIPO)) {
			chat(mc, "Ouvi \"" + fala.texto() + "\" mas o servidor não tem o mod FalaCraft.");
			return;
		}

		if (f.equals("parar") || f.equals("cancelar") || f.equals("para")) {
			ClientPlayNetworking.send(new PacoteVoz("parar", ""));
			chat(mc, "Ouvi \"" + fala.texto() + "\" -> parar");
			return;
		}

		Dicionario.Entrada e = Dicionario.procurar(f);
		chat(mc, "Ouvi \"" + fala.texto() + "\" [" + pct + "%] -> "
				+ (e == null ? "não reconheci como bloco/mob" : e.tipo() + " " + e.id() + " (enviado)"));
		mensagem(mc, "Ouvi: " + fala.texto());
		if (e != null) {
			ClientPlayNetworking.send(new PacoteVoz(e.tipo(), e.id()));
		}
	}

	private static String curto(String s) {
		return s.length() > 24 ? s.substring(0, 24) + "…" : s;
	}

	/** Mensagem curta de estado: aparece junto do medidor (ou sozinha se o medidor estiver desligado). */
	private static void mensagem(Minecraft mc, String texto) {
		ultimaMsg = texto;
		if (!mostrarMedidor) mostrarNaTela(mc, texto);
	}

	/** Mensagem no chat (fica registrada, o medidor não cobre). */
	private static void chat(Minecraft mc, String texto) {
		if (mc.player != null) {
			mc.player.sendSystemMessage(Component.literal("[FalaCraft] " + texto));
		}
	}

	private static void mostrarNaTela(Minecraft mc, String texto) {
		if (mc.player != null) {
			mc.player.sendOverlayMessage(Component.literal(texto));
		}
	}
}
