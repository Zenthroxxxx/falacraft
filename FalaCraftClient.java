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

	private static KeyMapping teclaMicrofone;

	@Override
	public void onInitializeClient() {
		teclaMicrofone = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.falacraft.microfone", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, KeyMapping.Category.MISC));

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (teclaMicrofone.consumeClick()) {
				boolean desligado = Reconhecedor.alternarPausa();
				mensagem(mc, desligado ? "Microfone DESLIGADO" : "Microfone LIGADO");
			}
		});

		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
			Dicionario.construir();
			Reconhecedor.iniciar(
					frase -> mc.execute(() -> tratar(mc, frase)),
					aviso -> mc.execute(() -> mensagem(mc, aviso)));
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> Reconhecedor.parar());
	}

	private static void tratar(Minecraft mc, String frase) {
		if (mc.player == null) return;
		String f = Dicionario.normalizar(frase);
		if (f.isBlank()) return;

		if (!ClientPlayNetworking.canSend(PacoteVoz.TIPO)) {
			mensagem(mc, "Este servidor não tem o mod FalaCraft.");
			return;
		}

		if (f.equals("parar") || f.equals("cancelar") || f.equals("para")) {
			ClientPlayNetworking.send(new PacoteVoz("parar", ""));
			return;
		}

		Dicionario.Entrada e = Dicionario.procurar(f);
		mensagem(mc, "Ouvi: " + frase + (e == null ? "  (não reconheci)" : "  ->  " + e.id()));
		if (e != null) {
			ClientPlayNetworking.send(new PacoteVoz(e.tipo(), e.id()));
		}
	}

	private static void mensagem(Minecraft mc, String texto) {
		if (mc.player != null) {
			mc.player.sendOverlayMessage(Component.literal(texto));
		}
	}
}
