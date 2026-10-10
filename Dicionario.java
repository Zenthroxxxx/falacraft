package br.falacraft.client;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;

import java.text.Normalizer;
import java.util.*;

/**
 * Monta o "dicionário" falado -> bloco/mob usando os nomes traduzidos do próprio jogo.
 * Com o idioma do Minecraft em Português (Brasil), tudo que existe no jogo vira palavra reconhecível.
 */
public final class Dicionario {

	public record Entrada(String tipo, String id) { }

	private static final Map<String, Entrada> MAPA = new HashMap<>();

	private Dicionario() { }

	public static String normalizar(String s) {
		String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
		return n.replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
	}

	public static void construir() {
		MAPA.clear();
		Language lang = Language.getInstance();

		for (Block b : BuiltInRegistries.BLOCK) {
			if (b.defaultBlockState().isAir()) continue;
			adicionar(lang, b.getDescriptionId(), "bloco", BuiltInRegistries.BLOCK.getKey(b).toString(), false);
		}
		// mobs vêm depois e têm prioridade se o nome for igual ao de um bloco
		for (EntityType<?> t : BuiltInRegistries.ENTITY_TYPE) {
			if (!t.canSummon()) continue;
			adicionar(lang, t.getDescriptionId(), "mob", BuiltInRegistries.ENTITY_TYPE.getKey(t).toString(), true);
		}

		// atalhos: "bloco de ferro" também responde a só "ferro", etc.
		for (Map.Entry<String, Entrada> en : new ArrayList<>(MAPA.entrySet())) {
			String nome = en.getKey();
			if (nome.startsWith("bloco de ")) MAPA.putIfAbsent(nome.substring(9), en.getValue());
			else if (nome.startsWith("bloco do ")) MAPA.putIfAbsent(nome.substring(9), en.getValue());
		}

		// garante o exemplo principal mesmo se o idioma não estiver em português
		MAPA.put("bau", new Entrada("bloco", "minecraft:chest"));
	}

	private static void adicionar(Language lang, String chave, String tipo, String id, boolean sobrescrever) {
		if (!lang.has(chave)) return;
		String nome = normalizar(lang.getOrDefault(chave));
		if (nome.isEmpty()) return;
		Entrada e = new Entrada(tipo, id);
		if (sobrescrever) MAPA.put(nome, e); else MAPA.putIfAbsent(nome, e);
	}

	public static int tamanho() {
		return MAPA.size();
	}

	/** Procura na frase falada o nome de algum bloco/mob. Retorna null se não achar. */
	public static Entrada procurar(String frase) {
		String[] t = frase.split(" ");

		// 1) nome exato (testa trechos de até 5 palavras, os maiores primeiro)
		for (int n = Math.min(5, t.length); n >= 1; n--) {
			for (int i = 0; i + n <= t.length; i++) {
				Entrada e = exato(String.join(" ", Arrays.copyOfRange(t, i, i + n)));
				if (e != null) return e;
			}
		}

		// 2) aproximado (o reconhecedor às vezes erra uma letra)
		Entrada melhor = null;
		int melhorDist = Integer.MAX_VALUE;
		for (int n = Math.min(3, t.length); n >= 1; n--) {
			for (int i = 0; i + n <= t.length; i++) {
				String c = String.join(" ", Arrays.copyOfRange(t, i, i + n));
				if (c.length() < 5) continue;
				int max = c.length() >= 9 ? 2 : 1;
				for (Map.Entry<String, Entrada> en : MAPA.entrySet()) {
					String nome = en.getKey();
					if (Math.abs(nome.length() - c.length()) > max) continue;
					int d = distancia(c, nome, max);
					if (d <= max && d < melhorDist) {
						melhorDist = d;
						melhor = en.getValue();
					}
				}
			}
		}
		return melhor;
	}

	private static Entrada exato(String c) {
		if (c.length() < 3) return null;
		Entrada e = MAPA.get(c);
		if (e == null && c.endsWith("s")) e = MAPA.get(c.substring(0, c.length() - 1));   // zumbis -> zumbi
		if (e == null && c.endsWith("es")) e = MAPA.get(c.substring(0, c.length() - 2));  // aldeoes etc.
		return e;
	}

	/** Distância de Levenshtein com saída antecipada. */
	private static int distancia(String a, String b, int max) {
		int[] prev = new int[b.length() + 1];
		int[] cur = new int[b.length() + 1];
		for (int j = 0; j <= b.length(); j++) prev[j] = j;
		for (int i = 1; i <= a.length(); i++) {
			cur[0] = i;
			int minLinha = cur[0];
			for (int j = 1; j <= b.length(); j++) {
				int custo = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
				cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + custo);
				minLinha = Math.min(minLinha, cur[j]);
			}
			if (minLinha > max) return max + 1;
			int[] tmp = prev; prev = cur; cur = tmp;
		}
		return prev[b.length()];
	}
}
