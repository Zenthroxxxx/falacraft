package br.falacraft;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

public class FalaCraft implements ModInitializer {

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.playC2S().register(PacoteVoz.TIPO, PacoteVoz.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(PacoteVoz.TIPO, (pacote, contexto) -> {
			ServerPlayer jogador = contexto.player();
			contexto.server().execute(() -> tratar(jogador, pacote));
		});

		ServerTickEvents.END_SERVER_TICK.register(servidor -> Transformador.tick());
	}

	private static void tratar(ServerPlayer jogador, PacoteVoz pacote) {
		if (pacote.tipo().equals("parar")) {
			Transformador.cancelar(jogador);
			return;
		}
		Identifier id = Identifier.tryParse(pacote.id());
		if (id == null) return;

		switch (pacote.tipo()) {
			case "bloco" -> BuiltInRegistries.BLOCK.getOptional(id)
					.ifPresent(bloco -> Transformador.iniciar(jogador, bloco));
			case "mob" -> BuiltInRegistries.ENTITY_TYPE.getOptional(id)
					.ifPresent(tipo -> Invocador.invocar(jogador, tipo));
			default -> { }
		}
	}
}
