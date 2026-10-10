package br.falacraft;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.*;

/**
 * Transforma todos os blocos (não-ar) ao redor do jogador em outro bloco.
 * Faz o trabalho aos poucos (orçamento de tempo por tick) para não travar o servidor.
 */
public final class Transformador {

	// ====== AJUSTE AQUI ======
	/** Raio em chunks para cada lado (10 = 21x21 chunks). */
	static final int RAIO_CHUNKS = 10;
	/** Quantos blocos para baixo/cima do jogador serão transformados. */
	static final int BLOCOS_ABAIXO = 12;
	static final int BLOCOS_ACIMA = 24;
	/** Tempo máximo gasto por tick (em nanossegundos). 25 ms = ~metade de um tick. */
	static final long ORCAMENTO_NS = 25_000_000L;
	// =========================

	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
	private static final Map<UUID, Tarefa> TAREFAS = new HashMap<>();

	private Transformador() { }

	static void iniciar(ServerPlayer jogador, Block bloco) {
		ServerLevel nivel = (ServerLevel) jogador.level();
		BlockPos p = jogador.blockPosition();
		int cx = p.getX() >> 4;
		int cz = p.getZ() >> 4;

		List<int[]> chunks = new ArrayList<>();
		for (int dx = -RAIO_CHUNKS; dx <= RAIO_CHUNKS; dx++) {
			for (int dz = -RAIO_CHUNKS; dz <= RAIO_CHUNKS; dz++) {
				chunks.add(new int[] { cx + dx, cz + dz, dx * dx + dz * dz });
			}
		}
		chunks.sort(Comparator.comparingInt(c -> c[2])); // do mais perto para o mais longe

		int minY = Math.max(nivel.getMinY(), p.getY() - BLOCOS_ABAIXO);
		int maxY = Math.min(nivel.getMaxY(), p.getY() + BLOCOS_ACIMA);

		TAREFAS.put(jogador.getUUID(), new Tarefa(jogador, nivel, bloco.defaultBlockState(),
				chunks, minY, maxY, p.immutable(), p.above()));

		jogador.sendSystemMessage(Component.literal("[FalaCraft] Transformando tudo em ").append(bloco.getName()).append("... (fale \"parar\" para cancelar)"));
	}

	static void cancelar(ServerPlayer jogador) {
		if (TAREFAS.remove(jogador.getUUID()) != null) {
			jogador.sendSystemMessage(Component.literal("[FalaCraft] Transformação cancelada."));
		}
	}

	static void tick() {
		if (TAREFAS.isEmpty()) return;
		long limite = System.nanoTime() + ORCAMENTO_NS;

		Iterator<Tarefa> it = TAREFAS.values().iterator();
		while (it.hasNext()) {
			Tarefa t = it.next();
			if (t.jogador.isRemoved()) {
				it.remove();
				continue;
			}
			if (t.executar(limite)) {
				t.jogador.sendSystemMessage(Component.literal("[FalaCraft] Pronto! " + t.total + " blocos transformados."));
				it.remove();
			}
			if (System.nanoTime() >= limite) break;
		}
	}

	private static final class Tarefa {
		final ServerPlayer jogador;
		final ServerLevel nivel;
		final BlockState estado;
		final List<int[]> chunks;
		final int minY, maxY;
		final BlockPos pe, cabeca; // posições do jogador: não mexe nelas
		int indiceChunk = 0;
		int cursor = 0;
		long total = 0;
		int ticks = 0;

		Tarefa(ServerPlayer jogador, ServerLevel nivel, BlockState estado, List<int[]> chunks,
			   int minY, int maxY, BlockPos pe, BlockPos cabeca) {
			this.jogador = jogador;
			this.nivel = nivel;
			this.estado = estado;
			this.chunks = chunks;
			this.minY = minY;
			this.maxY = maxY;
			this.pe = pe;
			this.cabeca = cabeca;
		}

		/** @return true quando terminou */
		boolean executar(long limite) {
			if (++ticks % 20 == 0) {
				int pct = indiceChunk * 100 / chunks.size();
				jogador.sendSystemMessage(Component.literal("Transformando... " + pct + "%"), true);
			}

			int porChunk = 256 * (maxY - minY + 1);
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

			while (indiceChunk < chunks.size()) {
				if (System.nanoTime() >= limite) return false;

				int[] c = chunks.get(indiceChunk);
				LevelChunk chunk = nivel.getChunkSource().getChunkNow(c[0], c[1]);
				if (chunk == null) {
					chunk = nivel.getChunk(c[0], c[1]); // carrega/gera (pesado, então 1 por vez)
				}

				while (cursor < porChunk) {
					int y = minY + cursor / 256;
					int r = cursor & 255;
					pos.set((c[0] << 4) + (r & 15), y, (c[1] << 4) + (r >> 4));
					cursor++;

					BlockState atual = chunk.getBlockState(pos);
					if (!atual.isAir() && atual != estado && !pos.equals(pe) && !pos.equals(cabeca)) {
						nivel.setBlock(pos, estado, FLAGS);
						total++;
					}
					if ((cursor & 511) == 0 && System.nanoTime() >= limite) return false;
				}
				cursor = 0;
				indiceChunk++;
			}
			return true;
		}
	}
}
