package br.falacraft;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FalaCraft implements ModInitializer {

	public static final Logger LOG = LoggerFactory.getLogger("FalaCraft");

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.serverboundPlay().register(PacoteVoz.TIPO, PacoteVoz.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(PacoteVoz.TIPO, (pacote, contexto) -> {
			ServerPlayer jogador = contexto.player();
			contexto.server().execute(() -> tratar(jogador, pacote));
		});

		ServerTickEvents.END_SERVER_TICK.register(servidor -> Transformador.tick());
		LOG.info("FalaCraft carregado.");
	}

	private static void tratar(ServerPlayer jogador, PacoteVoz pacote) {
		LOG.info("Pacote recebido de {}: {} {}", jogador.getName().getString(), pacote.tipo(), pacote.id());
		jogador.sendSystemMessage(Component.literal("[FalaCraft] Servidor recebeu: " + pacote.tipo() + " " + pacote.id()));

		if (pacote.tipo().equals("parar")) {
			Transformador.cancelar(jogador);
			return;
		}
		Identifier id = Identifier.tryParse(pacote.id());
		if (id == null) {
			jogador.sendSystemMessage(Component.literal("[FalaCraft] Id inválido: " + pacote.id()));
			return;
		}

		switch (pacote.tipo()) {
			case "bloco" -> BuiltInRegistries.BLOCK.getOptional(id).ifPresentOrElse(
					bloco -> Transformador.iniciar(jogador, bloco),
					() -> jogador.sendSystemMessage(Component.literal("[FalaCraft] Bloco não existe: " + id)));
			case "mob" -> BuiltInRegistries.ENTITY_TYPE.getOptional(id).ifPresentOrElse(
					tipo -> Invocador.invocar(jogador, tipo),
					() -> jogador.sendSystemMessage(Component.literal("[FalaCraft] Mob não existe: " + id)));
			default -> jogador.sendSystemMessage(Component.literal("[FalaCraft] Tipo desconhecido: " + pacote.tipo()));
		}
	}
}
