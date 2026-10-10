package br.falacraft;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;

import java.util.Set;

public final class Invocador {

	/** Quantos mobs aparecem quando você fala o nome. */
	static final int QUANTIDADE = 20;
	/** Mobs-chefe: aparece só 1 (20 Dragões do Fim quebrariam o jogo). */
	private static final Set<Identifier> SO_UM = Set.of(
			Identifier.withDefaultNamespace("ender_dragon"),
			Identifier.withDefaultNamespace("wither"));

	private Invocador() { }

	static void invocar(ServerPlayer jogador, EntityType<?> tipo) {
		if (!tipo.canSummon()) {
			jogador.sendSystemMessage(Component.literal("[FalaCraft] Isso não pode ser invocado."));
			return;
		}
		ServerLevel nivel = (ServerLevel) jogador.level();
		RandomSource aleatorio = jogador.getRandom();
		Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(tipo);
		int quantidade = SO_UM.contains(id) ? 1 : QUANTIDADE;

		BlockPos centro = jogador.blockPosition();
		int criados = 0;
		for (int i = 0; i < quantidade; i++) {
			BlockPos base = centro.offset(aleatorio.nextInt(-8, 9), 0, aleatorio.nextInt(-8, 9));
			BlockPos destino = acharLugar(nivel, base, centro);

			Entity e = tipo.spawn(nivel, destino, EntitySpawnReason.COMMAND);
			if (e == null) continue;
			if (!(e instanceof Mob)) { // só vale mob de verdade
				e.discard();
				jogador.sendSystemMessage(Component.literal("[FalaCraft] Isso não é um mob."));
				return;
			}
			criados++;
		}

		FalaCraft.LOG.info("Invocados {} de {}", criados, id);
		jogador.sendSystemMessage(Component.literal("[FalaCraft] " + criados + "x ").append(tipo.getDescription()));
		if (criados == 0) {
			jogador.sendSystemMessage(Component.literal("[FalaCraft] Nenhum apareceu. O mundo está em algum modo que bloqueia?"));
		} else if (tipo.getCategory() == MobCategory.MONSTER && nivel.getDifficulty() == Difficulty.PEACEFUL) {
			jogador.sendSystemMessage(Component.literal(
					"[FalaCraft] Atenção: no modo PACÍFICO monstros hostis somem na hora. Mude a dificuldade ou fale um mob pacífico (vaca, ovelha)."));
		}
	}

	/** Procura um lugar com chão firme e 2 blocos de espaço livre perto da posição sorteada. */
	private static BlockPos acharLugar(ServerLevel nivel, BlockPos base, BlockPos reserva) {
		for (int dy = -4; dy <= 6; dy++) {
			BlockPos p = base.offset(0, dy, 0);
			BlockPos abaixo = p.below();
			boolean chao = !nivel.getBlockState(abaixo).getCollisionShape(nivel, abaixo).isEmpty();
			boolean livre = nivel.getBlockState(p).getCollisionShape(nivel, p).isEmpty()
					&& nivel.getBlockState(p.above()).getCollisionShape(nivel, p.above()).isEmpty();
			if (chao && livre) return p;
		}
		return reserva;
	}
}
