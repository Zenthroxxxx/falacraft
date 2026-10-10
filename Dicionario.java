package br.falacraft.client;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;

import java.text.Normalizer;
import java.util.*;

/**
 * Monta o "dicionário" falado -> bloco/mob.
 * 1) usa os nomes traduzidos do próprio jogo (idioma Português Brasil);
 * 2) acrescenta uma tabela própria em português, que funciona mesmo com o jogo em outro idioma.
 */
public final class Dicionario {

	public record Entrada(String tipo, String id) { }

	private static final Map<String, Entrada> MAPA = new HashMap<>();
	/** Frases que o reconhecedor de voz vai aceitar (com e sem acento). */
	private static final Set<String> FRASES = new LinkedHashSet<>();

	private static final String[][] ALIAS_MOBS = {
		{"zumbi", "zombie"}, {"esqueleto", "skeleton"}, {"aranha", "spider"},
		{"creeper", "creeper"}, {"criper", "creeper"}, {"cripper", "creeper"},
		{"vaca", "cow"}, {"porco", "pig"}, {"ovelha", "sheep"}, {"galinha", "chicken"},
		{"lobo", "wolf"}, {"cachorro", "wolf"}, {"gato", "cat"}, {"cavalo", "horse"},
		{"burro", "donkey"}, {"mula", "mule"}, {"coelho", "rabbit"}, {"abelha", "bee"},
		{"raposa", "fox"}, {"panda", "panda"}, {"urso polar", "polar_bear"}, {"lula", "squid"},
		{"golfinho", "dolphin"}, {"tartaruga", "turtle"}, {"papagaio", "parrot"}, {"morcego", "bat"},
		{"aldeão", "villager"}, {"saqueador", "pillager"}, {"bruxa", "witch"},
		{"enderman", "enderman"}, {"ghast", "ghast"}, {"blaze", "blaze"}, {"slime", "slime"},
		{"cubo de magma", "magma_cube"}, {"piglin", "piglin"}, {"golem de ferro", "iron_golem"},
		{"golem de neve", "snow_golem"}, {"aranha de caverna", "cave_spider"}, {"traça", "silverfish"},
		{"endermite", "endermite"}, {"guardião", "guardian"}, {"shulker", "shulker"},
		{"fantasma", "phantom"}, {"afogado", "drowned"}, {"husk", "husk"}, {"vingador", "vindicator"},
		{"invocador", "evoker"}, {"devastador", "ravager"}, {"warden", "warden"}, {"allay", "allay"},
		{"axolote", "axolotl"}, {"sapo", "frog"}, {"camelo", "camel"}, {"cabra", "goat"},
		{"lhama", "llama"}, {"ocelote", "ocelot"}, {"dragão do fim", "ender_dragon"}, {"wither", "wither"},
		{"zumbi aldeão", "zombie_villager"}, {"esqueleto errante", "stray"}, {"bacalhau", "cod"},
		{"salmão", "salmon"}, {"vaca de cogumelo", "mooshroom"}, {"golem", "iron_golem"},
	};

	private static final String[][] ALIAS_BLOCOS = {
		{"pedra", "stone"}, {"terra", "dirt"}, {"grama", "grass_block"}, {"areia", "sand"},
		{"cascalho", "gravel"}, {"pedregulho", "cobblestone"}, {"vidro", "glass"},
		{"diamante", "diamond_block"}, {"ouro", "gold_block"}, {"ferro", "iron_block"},
		{"esmeralda", "emerald_block"}, {"lápis", "lapis_block"}, {"redstone", "redstone_block"},
		{"carvão", "coal_block"}, {"obsidiana", "obsidian"}, {"lava", "lava"}, {"água", "water"},
		{"tnt", "tnt"}, {"netherrack", "netherrack"}, {"neve", "snow_block"}, {"gelo", "ice"},
		{"cobre", "copper_block"}, {"baú", "chest"}, {"baú do ender", "ender_chest"},
		{"mesa de trabalho", "crafting_table"}, {"fornalha", "furnace"}, {"abóbora", "pumpkin"},
		{"melancia", "melon"}, {"fardo de feno", "hay_block"}, {"magma", "magma_block"},
		{"bloco de slime", "slime_block"}, {"mel", "honey_block"}, {"madeira", "oak_planks"},
		{"tábua", "oak_planks"}, {"tijolo", "bricks"}, {"bedrock", "bedrock"}, {"lã", "white_wool"},
		{"estante", "bookshelf"}, {"tocha", "torch"},
	};

	private Dicionario() { }

	public static String normalizar(String s) {
		String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
		return n.replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
	}

	public static void construir() {
		MAPA.clear();
		FRASES.clear();
		Language lang = Language.getInstance();

		for (Block b : BuiltInRegistries.BLOCK) {
			if (b.defaultBlockState().isAir()) continue;
			String chave = b.getDescriptionId();
			if (lang.has(chave)) {
				registrar(lang.getOrDefault(chave), new Entrada("bloco", BuiltInRegistries.BLOCK.getKey(b).toString()), false);
			}
		}
		// mobs vêm depois e têm prioridade se o nome for igual ao de um bloco
		for (EntityType<?> t : BuiltInRegistries.ENTITY_TYPE) {
			if (!t.canSummon()) continue;
			String chave = t.getDescriptionId();
			if (lang.has(chave)) {
				registrar(lang.getOrDefault(chave), new Entrada("mob", BuiltInRegistries.ENTITY_TYPE.getKey(t).toString()), true);
			}
		}

		// tabela própria em português (não sobrescreve o que o jogo já traduziu)
		for (String[] a : ALIAS_BLOCOS) aliasSe("bloco", a[0], a[1]);
		for (String[] a : ALIAS_MOBS) aliasSe("mob", a[0], a[1]);

		for (String c : new String[] { "parar", "cancelar", "para" }) FRASES.add(c);
	}

	private static void aliasSe(String tipo, String nome, String idCurto) {
		Identifier id = Identifier.withDefaultNamespace(idCurto);
		boolean existe = tipo.equals("bloco")
				? BuiltInRegistries.BLOCK.getOptional(id).isPresent()
				: BuiltInRegistries.ENTITY_TYPE.getOptional(id).isPresent();
		if (existe) registrar(nome, new Entrada(tipo, id.toString()), false);
	}

	private static void registrar(String nomeOriginal, Entrada e, boolean sobrescrever) {
		String limpo = nomeOriginal.toLowerCase(Locale.ROOT)
				.replaceAll("[^\\p{L}\\p{N} ]", " ").replaceAll("\\s+", " ").trim();
		if (limpo.isEmpty()) return;
		String chave = normalizar(limpo);
		if (chave.isEmpty()) return;

		if (sobrescrever) MAPA.put(chave, e); else MAPA.putIfAbsent(chave, e);
		FRASES.add(limpo);
		FRASES.add(chave);

		// atalho: "bloco de ferro" também responde a só "ferro"
		if (limpo.startsWith("bloco de ") || limpo.startsWith("bloco do ")) {
			registrar(limpo.substring(9), e, false);
		}
	}

	public static int tamanho() {
		return MAPA.size();
	}

	/** Lista de frases para limitar o reconhecimento de voz ao vocabulário do jogo. */
	public static List<String> frasesParaVoz() {
		List<String> l = new ArrayList<>();
		for (String f : FRASES) if (f.length() >= 3) l.add(f);
		return l;
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

	/** Nome mais parecido com o que foi ouvido (só para diagnóstico). */
	public static String sugestao(String f) {
		String melhor = null;
		int md = Integer.MAX_VALUE;
		for (String nome : MAPA.keySet()) {
			if (Math.abs(nome.length() - f.length()) > 6) continue;
			int d = distancia(f, nome, 6);
			if (d < md) { md = d; melhor = nome; }
		}
		return (melhor == null || md > 6) ? null : melhor;
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
