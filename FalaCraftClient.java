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

	private static boolean mostrarMedidor = true;
	private static String ultimaMsg = "";
	private static int contador = 0;

	@Override
	public void onInitializeClient() {
		teclaMicrofone = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.falacraft.microfone", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, KeyMapping.Category.MISC));
		teclaTrocar = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.falacraft.trocar_microfone", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, KeyMapping.Category.MISC));
		teclaMedidor = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.falacraft.medidor", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, KeyMapping.Category.MISC));

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (teclaMicrofone.consumeClick()) {
				boolean desligado = Reconhecedor.alternarPausa();
				mensagem(mc, desligado ? "Microfone DESLIGADO" : "Microfone LIGADO");
			}
			while (teclaTrocar.consumeClick()) {
				mensagem(mc, "Trocando microfone...");
				String nome = Reconhecedor.trocarMicrofone();
				mensagem(mc, "Microfone: " + nome);
			}
			while (teclaMedidor.consumeClick()) {
				mostrarMedidor = !mostrarMedidor;
				ultimaMsg = mostrarMedidor ? "Medidor ligado" : "Medidor desligado";
				if (!mostrarMedidor) mostrarNaTela(mc, ultimaMsg);
			}

			if (mostrarMedidor && mc.player != null && ++contador % 4 == 0) {
				String estado = Reconhecedor.estaPausado() ? "MUDO | " : "";
				mostrarNaTela(mc, estado + "Mic: " + curto(Reconhecedor.nomeMicrofone())
						+ " | vol " + Reconhecedor.volumeAtual + " (precisa " + Reconhecedor.limiarAtual + ") | " + ultimaMsg);
			}
		});

		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
			Dicionario.construir();
			Reconhecedor.iniciar(
					fala -> mc.execute(() -> tratar(mc, fala)),
					aviso -> mc.execute(() -> mensagem(mc, aviso)));
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> Reconhecedor.parar());
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
			mensagem(mc, "Este servidor não tem o mod FalaCraft.");
			return;
		}

		if (f.equals("parar") || f.equals("cancelar") || f.equals("para")) {
			ClientPlayNetworking.send(new PacoteVoz("parar", ""));
			mensagem(mc, "Ouvi: " + fala.texto() + "  -> parar");
			return;
		}

		Dicionario.Entrada e = Dicionario.procurar(f);
		mensagem(mc, "Ouvi: " + fala.texto() + " [" + pct + "%]" + (e == null ? "  (não reconheci)" : "  ->  " + e.id()));
		if (e != null) {
			ClientPlayNetworking.send(new PacoteVoz(e.tipo(), e.id()));
		}
	}

	private static String curto(String s) {
		return s.length() > 24 ? s.substring(0, 24) + "…" : s;
	}

	/** Guarda a mensagem; com o medidor ligado ela aparece junto dele, senão aparece direto. */
	private static void mensagem(Minecraft mc, String texto) {
		ultimaMsg = texto;
		if (!mostrarMedidor) mostrarNaTela(mc, texto);
	}

	private static void mostrarNaTela(Minecraft mc, String texto) {
		if (mc.player != null) {
			mc.player.sendOverlayMessage(Component.literal(texto));
		}
	}
}
