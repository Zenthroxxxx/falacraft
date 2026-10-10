package br.falacraft;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Pacote cliente -> servidor. tipo = "bloco", "mob" ou "parar". id = ex: "minecraft:chest". */
public record PacoteVoz(String tipo, String id) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<PacoteVoz> TIPO =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("falacraft", "voz"));

	public static final StreamCodec<RegistryFriendlyByteBuf, PacoteVoz> CODEC = StreamCodec.composite(
			ByteBufCodecs.STRING_UTF8, PacoteVoz::tipo,
			ByteBufCodecs.STRING_UTF8, PacoteVoz::id,
			PacoteVoz::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TIPO;
	}
}
