// Copyright 2011 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.hughes.android.dictionary.parser;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import com.hughes.android.dictionary.engine.EntryTypeName;
import com.hughes.android.dictionary.engine.IndexBuilder;
import com.hughes.android.dictionary.engine.IndexedEntry;
import com.hughes.android.dictionary.engine.PairEntry;
import com.hughes.android.dictionary.engine.PairEntry.Pair;

public class EnWiktionaryXmlParser {
  
  private static final String TRANSLITERATION_FORMAT = " (tr. %s)";

  static final Logger LOG = Logger.getLogger(EnWiktionaryXmlParser.class.getName());
  
  // TODO: process {{ttbc}} lines
  
  static final Pattern partOfSpeechHeader = Pattern.compile(
      "Noun|Verb|Adjective|Adverb|Pronoun|Conjunction|Interjection|" +
      "Preposition|Proper noun|Article|Prepositional phrase|Acronym|" +
      "Abbreviation|Initialism|Contraction|Prefix|Suffix|Symbol|Letter|" +
      "Ligature|Idiom|Phrase|\\{\\{acronym\\}\\}|\\{\\{initialism\\}\\}|" +
      // These are @deprecated:
      "Noun form|Verb form|Adjective form|Nominal phrase|Noun phrase|" +
      "Verb phrase|Transitive verb|Intransitive verb|Reflexive verb|" +
      // These are extras I found:
      "Determiner|Numeral|Number|Cardinal number|Ordinal number|Proverb|" +
      "Particle|Interjection|Pronominal adverb" +
      "Han character|Hanzi|Hanja|Kanji|Katakana character|Syllable");
  
  final IndexBuilder enIndexBuilder;
  final IndexBuilder foreignIndexBuilder;
  final Pattern langPattern;
  final Pattern langCodePattern;
  final boolean swap;

  public EnWiktionaryXmlParser(final IndexBuilder enIndexBuilder, final IndexBuilder otherIndexBuilder, final Pattern langPattern, final Pattern langCodePattern, final boolean swap) {
    this.enIndexBuilder = enIndexBuilder;
    this.foreignIndexBuilder = otherIndexBuilder;
    this.langPattern = langPattern;
    this.langCodePattern = langCodePattern;
    this.swap = swap;
  }

  
  public void parse(final File file, final int pageLimit) throws IOException {
    int pageCount = 0;
    final DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(file)));
    while (true) {
      if (pageLimit >= 0 && pageCount >= pageLimit) {
        return;
      }
      
      final String title;
      try {
        title = dis.readUTF();
      } catch (EOFException e) {
        dis.close();
        return;
      }
      final String heading = dis.readUTF();
      final int bytesLength = dis.readInt();
      final byte[] bytes = new byte[bytesLength];
      dis.readFully(bytes);
      final String text = new String(bytes, "UTF8");
      
      parseSection(title, heading, text);

      ++pageCount;
      if (pageCount % 1000 == 0) {
        LOG.info("pageCount=" + pageCount);
      }
    }
  }
  
  private void parseSection(final String title, String heading, final String text) {
    if (title.startsWith("Wiktionary:") ||
        title.startsWith("Template:") ||
        title.startsWith("Appendix:") ||
        title.startsWith("Category:") ||
        title.startsWith("Index:") ||
        title.startsWith("MediaWiki:") ||
        title.startsWith("TransWiki:") ||
        title.startsWith("Citations:") ||
        title.startsWith("Concordance:") ||
        title.startsWith("Help:")) {
      return;
    }
    
    heading = heading.replaceAll("=", "").trim(); 
    if (heading.equals("English")) {
      doEnglishWord(title, text);
    } else if (langPattern.matcher(heading).find()){
      doForeignWord(heading, title, text);
    }
        
  }  // endPage()
  
  // -------------------------------------------------------------------------
  
  private void doEnglishWord(String title, String text) {
    
    String pos = null;
    int posDepth = -1;

    final WikiTokenizer wikiTokenizer = new WikiTokenizer(text);
    while (wikiTokenizer.nextToken() != null) {
      
      if (wikiTokenizer.isHeading()) {
        final String headerName = wikiTokenizer.headingWikiText();
        
        if (wikiTokenizer.headingDepth() <= posDepth) {
          pos = null;
          posDepth = -1;
        }
        
        if (partOfSpeechHeader.matcher(headerName).matches()) {
          posDepth = wikiTokenizer.headingDepth();
          pos = wikiTokenizer.headingWikiText();
          // TODO: if we're inside the POS section, we should handle the first title line...
          
        } else if (headerName.equals("Translations")) {
          if (pos == null) {
            LOG.warning("Translations without POS: " + title);
          }
          doTranslations(title, wikiTokenizer, pos);
        } else if (headerName.equals("Pronunciation")) {
          //doPronunciation(wikiLineReader);
        }
      }
    }
  }


  private static Set<String> encodings = new LinkedHashSet<String>(Arrays.asList("zh-ts",
      "sd-Arab", "ku-Arab", "Arab", "unicode", "Laoo", "ur-Arab", "Thai", 
      "fa-Arab", "Khmr", "zh-tsp", "Cyrl", "IPAchar", "ug-Arab", "ko-inline", 
      "Jpan", "Kore", "Hebr", "rfscript", "Beng", "Mong", "Knda", "Cyrs",
      "yue-tsj", "Mlym", "Tfng", "Grek", "yue-yue-j"));
  
  private void doTranslations(final String title, final WikiTokenizer wikiTokenizer, final String pos) {
    if (title.equals("absolutely")) {
      //System.out.println();
    }
    
    String topLevelLang = null;
    String sense = null;
    boolean done = false;
    while (wikiTokenizer.nextToken() != null) {
      if (wikiTokenizer.isHeading()) {
        wikiTokenizer.returnToLineStart();
        return;
      }
      if (done) {
        continue;
      }
      
      // Check whether we care about this line:
      
      if (wikiTokenizer.isFunction()) {
        final String functionName = wikiTokenizer.functionName();
        final List<String> positionArgs = wikiTokenizer.functionPositionArgs();
        
        if (functionName.equals("trans-top")) {
          sense = null;
          if (wikiTokenizer.functionPositionArgs().size() >= 1) {
            sense = positionArgs.get(0);
            // TODO: could emphasize words in [[brackets]] inside sense.
            sense = WikiTokenizer.toPlainText(sense);
            //LOG.info("Sense: " + sense);
          }
        } else if (functionName.equals("trans-bottom")) {
          sense = null;
        } else if (functionName.equals("trans-mid")) {
        } else if (functionName.equals("trans-see")) {
          // TODO: would also be nice...
        } else if (functionName.startsWith("picdic")) {
        } else if (functionName.startsWith("checktrans")) {
          done = true;
        } else if (functionName.startsWith("ttbc")) {
          wikiTokenizer.nextLine();
          // TODO: would be great to handle ttbc
          // TODO: Check this: done = true;
        } else {
          LOG.warning("Unexpected translation wikifunction: " + wikiTokenizer.token() + ", title=" + title);
        }
      } else if (wikiTokenizer.isListItem()) {
        final String line = wikiTokenizer.listItemWikiText();
        // This line could produce an output...
        
        if (line.contains("ich hoan dich gear")) {
          //System.out.println();
        }
        
        // First strip the language and check whether it matches.
        // And hold onto it for sub-lines.
        final int colonIndex = line.indexOf(":");
        if (colonIndex == -1) {
          continue;
        }
        
        final String lang = trim(WikiTokenizer.toPlainText(line.substring(0, colonIndex)));
        final boolean appendLang;
        if (wikiTokenizer.listItemPrefix().length() == 1) {
          topLevelLang = lang;
          final boolean thisFind = langPattern.matcher(lang).find();
          if (!thisFind) {
            continue;
          }
          appendLang = !langPattern.matcher(lang).matches();
        } else if (topLevelLang == null) {
          continue;
        } else {
          // Two-level -- the only way we won't append is if this second level matches exactly.
          if (!langPattern.matcher(lang).matches() && !langPattern.matcher(topLevelLang).find()) {
            continue;
          }
          appendLang = !langPattern.matcher(lang).matches();
        }
        
        String rest = line.substring(colonIndex + 1).trim();
        if (rest.length() > 0) {
          doTranslationLine(line, appendLang ? lang : null, title, pos, sense, rest);
        }
        
      } else if (wikiTokenizer.remainderStartsWith("''See''")) {
        wikiTokenizer.nextLine();
        LOG.fine("Skipping line: " + wikiTokenizer.token());
      } else if (wikiTokenizer.isWikiLink()) {
        final String wikiLink = wikiTokenizer.wikiLinkText();
        if (wikiLink.contains(":") && wikiLink.contains(title)) {
        } else if (wikiLink.contains("Category:")) {
        } else  {
          LOG.warning("Unexpected wikiLink: " + wikiTokenizer.token() + ", title=" + title);
        }
      } else if (wikiTokenizer.isNewline() || wikiTokenizer.isMarkup() || wikiTokenizer.isComment()) {
      } else {
        final String token = wikiTokenizer.token();
        if (token.equals("----")) { 
        } else {
          LOG.warning("Unexpected translation token: " + wikiTokenizer.token() + ", title=" + title);
        }
      }
      
    }
  }
  
  private static <T> T get(final List<T> list, final int index, final T defaultValue) {
    return index < list.size() ? list.get(index) : defaultValue;
  }

  private static <T> T get(final List<T> list, final int index) {
    return get(list, index, null);
  }

  private static <T> T remove(final List<T> list, final int index, final T defaultValue) {
    return index < list.size() ? list.remove(index) : defaultValue;
  }
  
  
  static final class Callback implements WikiTokenizer.Callback {
    public Callback(IndexedEntry indexedEntry, IndexBuilder defaultIndexBuilder,
        StringBuilder builder, Map<String, WikiFunctionCallback> functionCallbacks) {
      this.indexedEntry = indexedEntry;
      this.defaultIndexBuilder = defaultIndexBuilder;
      this.builder = builder;
      this.functionCallbacks = functionCallbacks;
    }

    final IndexedEntry indexedEntry;
    final IndexBuilder defaultIndexBuilder;
    final StringBuilder builder;
    final Map<String,WikiFunctionCallback> functionCallbacks;
    
    // TODO: the classes of text are wrong....
    
    @Override
    public void onPlainText(WikiTokenizer wikiTokenizer) {
      final String plainText = wikiTokenizer.token(); 
      builder.append(plainText);
      defaultIndexBuilder.addEntryWithString(indexedEntry, plainText, EntryTypeName.WIKTIONARY_TRANSLATION_OTHER_TEXT);
    }

    @Override
    public void onWikiLink(WikiTokenizer wikiTokenizer) {
      final String plainText = wikiTokenizer.wikiLinkText(); 
      builder.append(plainText);
      // TODO: should check for English before appending.
      defaultIndexBuilder.addEntryWithString(indexedEntry, plainText, EntryTypeName.WIKTIONARY_TRANSLATION_WIKI_TEXT);
    }

    @Override
    public void onFunction(String functionName,
        List<String> functionPositionArgs, Map<String, String> functionNamedArgs) {
    }

    @Override
    public void onMarkup(WikiTokenizer wikiTokenizer) {
      // Do nothing.
    }

    @Override
    public void onComment(WikiTokenizer wikiTokenizer) {
      // Do nothing.
    }

    @Override
    public void onNewline(WikiTokenizer wikiTokenizer) {
      assert false;
    }

    @Override
    public void onHeading(WikiTokenizer wikiTokenizer) {
      assert false;
    }

    @Override
    public void onListItem(WikiTokenizer wikiTokenizer) {
      assert false;
    }
  }

  private void doTranslationLine(final String line, final String lang, final String title, final String pos, final String sense, final String rest) {
    // Good chance we'll actually file this one...
    final PairEntry pairEntry = new PairEntry();
    final IndexedEntry indexedEntry = new IndexedEntry(pairEntry);
    
    final StringBuilder foreignText = new StringBuilder();
    final WikiTokenizer wikiTokenizer = new WikiTokenizer(rest, false);
    while (wikiTokenizer.nextToken() != null) {
      
      if (wikiTokenizer.isPlainText()) {
        final String plainText = wikiTokenizer.token(); 
        foreignText.append(plainText);
        foreignIndexBuilder.addEntryWithString(indexedEntry, plainText, EntryTypeName.WIKTIONARY_TRANSLATION_OTHER_TEXT);
        
      } else if (wikiTokenizer.isWikiLink()) {
        final String plainText = wikiTokenizer.wikiLinkText(); 
        foreignText.append(plainText);
        // TODO: should check for English before appending.
        foreignIndexBuilder.addEntryWithString(indexedEntry, plainText, EntryTypeName.WIKTIONARY_TRANSLATION_WIKI_TEXT);

      } else if (wikiTokenizer.isFunction()) {
        final String functionName = wikiTokenizer.functionName();
        final List<String> args = wikiTokenizer.functionPositionArgs();
        final Map<String,String> namedArgs = wikiTokenizer.functionNamedArgs();
        
        if (functionName.equals("t") || functionName.equals("t+") || functionName.equals("t-") || functionName.equals("tø") || functionName.equals("apdx-t")) {
          if (args.size() < 2) {
            LOG.warning("{{t}} with too few args: " + line + ", title=" + title);
            continue;
          }
          final String langCode = get(args, 0);
          final String word = get(args, 1);
          final String gender = get(args, 2);
          final String transliteration = namedArgs.get("tr");
          if (foreignText.length() > 0) {
            foreignText.append("");
          }
          foreignText.append(word);
          foreignIndexBuilder.addEntryWithString(indexedEntry, word, EntryTypeName.WIKTIONARY_TITLE_SINGLE, EntryTypeName.WIKTIONARY_TITLE_MULTI);
          if (gender != null) {
            foreignText.append(String.format(" {%s}", gender));
          }
          if (transliteration != null) {
            foreignText.append(String.format(TRANSLITERATION_FORMAT, transliteration));
            foreignIndexBuilder.addEntryWithString(indexedEntry, transliteration, EntryTypeName.WIKTIONARY_TRANSLITERATION);
          }
        } else if (functionName.equals("qualifier")) {
          if (args.size() == 0) {
           foreignText.append(wikiTokenizer.token()); 
          } else { 
            String qualifier = args.get(0);
            if (!namedArgs.isEmpty() || args.size() > 1) {
              LOG.warning("weird qualifier: " + line);
            }
            // Unindexed!
            foreignText.append("(").append(qualifier).append(")");
          }
        } else if (encodings.contains(functionName)) {
          foreignText.append("").append(args.get(0));
          foreignIndexBuilder.addEntryWithString(indexedEntry, args.get(0), EntryTypeName.WIKTIONARY_TRANSLATION_OTHER_TEXT);
        } else if (isGender(functionName)) {
          appendGender(foreignText, functionName, args);
        } else if (functionName.equals("g")) {
          foreignText.append("{g}");
        } else if (functionName.equals("l")) {
          // encodes text in various langs.
          // lang is arg 0.
          foreignText.append("").append(args.get(1));
          foreignIndexBuilder.addEntryWithString(indexedEntry, args.get(1), EntryTypeName.WIKTIONARY_TRANSLATION_OTHER_TEXT);
          // TODO: transliteration
        } else if (functionName.equals("term")) {
          // cross-reference to another dictionary
          foreignText.append("").append(args.get(0));
          foreignIndexBuilder.addEntryWithString(indexedEntry, args.get(0), EntryTypeName.WIKTIONARY_TRANSLATION_OTHER_TEXT);
          // TODO: transliteration
        } else if (functionName.equals("italbrac") || functionName.equals("gloss")) {
          // TODO: put this text aside to use it.
          foreignText.append("[").append(args.get(0)).append("]");
          foreignIndexBuilder.addEntryWithString(indexedEntry, args.get(0), EntryTypeName.WIKTIONARY_TRANSLATION_OTHER_TEXT);
        } else if (functionName.equals("ttbc")) {
          LOG.warning("Unexpected {{ttbc}}");
        } else if (functionName.equals("trreq")) {
        } else if (functionName.equals("not used")) {
          foreignText.append("(not used)");
        } else if (functionName.equals("t-image")) {
          // American sign language
        } else {
          // Unindexed!
          namedArgs.keySet().removeAll(USELESS_WIKI_ARGS);
          WikiTokenizer.appendFunction(foreignText.append("{{"), functionName, args, namedArgs).append("}}");
        }
        
      } else if (wikiTokenizer.isNewline()) {
      } else if (wikiTokenizer.isComment()) {
      } else if (wikiTokenizer.isMarkup()) {
      } else {
        LOG.warning("Bad translation token: " + wikiTokenizer.token());
      }
    }
    if (foreignText.length() == 0) {
      LOG.warning("Empty foreignText: " + line);
      return;
    }
    
    if (lang != null) {
      foreignText.insert(0, String.format("(%s) ", lang));
    }
    
    StringBuilder englishText = new StringBuilder();
    
    englishText.append(title);
    if (sense != null) {
      englishText.append(" (").append(sense).append(")");
      enIndexBuilder.addEntryWithString(indexedEntry, sense, EntryTypeName.WIKTIONARY_TRANSLATION_SENSE, EntryTypeName.WIKTIONARY_TRANSLATION_SENSE);
    }
    if (pos != null) {
      englishText.append(" (").append(pos.toLowerCase()).append(")");
    }
    enIndexBuilder.addEntryWithString(indexedEntry, title, EntryTypeName.WIKTIONARY_TITLE_SINGLE, EntryTypeName.WIKTIONARY_TITLE_MULTI);
    
    final Pair pair = new Pair(trim(englishText.toString()), trim(foreignText.toString()), swap);
    pairEntry.pairs.add(pair);
    if (!pairsAdded.add(pair.toString())) {
      LOG.warning("Duplicate pair: " + pair.toString());
    }
    if (pair.toString().equals("libero {m} :: free (adjective)")) {
      System.out.println();
    }

  }


  private void appendGender(final StringBuilder otherText,
      final String functionName, final List<String> args) {
    otherText.append("{");
    otherText.append(functionName);
    for (int i = 0; i < args.size(); ++i) {
      otherText.append("|").append(args.get(i));
    }
    otherText.append("}");
  }


  private boolean isGender(final String functionName) {
    return functionName.equals("m") || functionName.equals("f") || functionName.equals("n") || functionName.equals("p");
  }
  
  Set<String> pairsAdded = new LinkedHashSet<String>();
  
  // -------------------------------------------------------------------------
  
  private void doForeignWord(final String lang, final String title, final String text) {
    final WikiTokenizer wikiTokenizer = new WikiTokenizer(text);
    while (wikiTokenizer.nextToken() != null) {
      if (wikiTokenizer.isHeading()) {
        final String headingName = wikiTokenizer.headingWikiText();
        if (headingName.equals("Translations")) {
          LOG.warning("Translations not in English section: " + title);
        } else if (headingName.equals("Pronunciation")) {
          //doPronunciation(wikiLineReader);
        } else if (partOfSpeechHeader.matcher(headingName).matches()) {
          doForeignPartOfSpeech(lang, title, headingName, wikiTokenizer.headingDepth(), wikiTokenizer);
        }
      } else {
      }
    }
  }
  
  static final class ListSection {
    final String firstPrefix;
    final String firstLine;
    final List<String> nextPrefixes = new ArrayList<String>();
    final List<String> nextLines = new ArrayList<String>();
    
    public ListSection(String firstPrefix, String firstLine) {
      this.firstPrefix = firstPrefix;
      this.firstLine = firstLine;
    }

    @Override
    public String toString() {
      return firstPrefix + firstLine + "{ " + nextPrefixes + "}";
    }
  }


  int foreignCount = 0;
  private void doForeignPartOfSpeech(final String lang, String title, final String posHeading, final int posDepth, WikiTokenizer wikiTokenizer) {
    if (++foreignCount % 1000 == 0) {
      LOG.info("***" + lang + ", " + title + ", pos=" + posHeading + ", foreignCount=" + foreignCount);
    }
    if (title.equals("moro")) {
      System.out.println();
    }
    
    boolean titleAppended = false;
    final StringBuilder foreignBuilder = new StringBuilder();
    final Collection<String> wordForms = new ArrayList<String>();
    final List<ListSection> listSections = new ArrayList<ListSection>();
    
    try {
    
    ListSection lastListSection = null;
    
    int currentHeadingDepth = posDepth;
    while (wikiTokenizer.nextToken() != null) {
      if (wikiTokenizer.isHeading()) {
        currentHeadingDepth = wikiTokenizer.headingDepth();
        
        if (currentHeadingDepth <= posDepth) {
          wikiTokenizer.returnToLineStart();
          return;
        }
      }
      
      if (currentHeadingDepth > posDepth) {
        // TODO: deal with other neat info sections
        continue;
      }
      
      if (wikiTokenizer.isFunction()) {
        final String name = wikiTokenizer.functionName();
        final List<String> args = wikiTokenizer.functionPositionArgs();
        final Map<String,String> namedArgs = wikiTokenizer.functionNamedArgs();
        // First line is generally a repeat of the title with some extra information.
        // We need to build up the left side (foreign text, tokens) separately from the
        // right side (English).  The left-side may get paired with multiple right sides.
        // The left side should get filed under every form of the word in question (singular, plural).
        
        // For verbs, the conjugation comes later on in a deeper section.
        // Ideally, we'd want to file every English entry with the verb
        // under every verb form coming from the conjugation.
        // Ie. under "fa": see: "make :: fare" and "do :: fare"
        // But then where should we put the conjugation table?
        // I think just under fare.  But then we need a way to link to the entry (actually the row, since entries doesn't show up!)
        // for the conjugation table from "fa".
        // Would like to be able to link to a lang#token.
        if (isGender(name)) {
          appendGender(foreignBuilder, name, args);
        } else if (name.equals("wikipedia")) {
          namedArgs.remove("lang");
          if (args.size() > 1 || !namedArgs.isEmpty()) {
            // Unindexed!
            foreignBuilder.append(wikiTokenizer.token());
          } else if (args.size() == 1) {
            foreignBuilder.append(wikiTokenizer.token());
          } else {
            //foreignBuilder.append(title);
          }
        } else if (name.equals("attention") || name.equals("zh-attention")) {
          // See: http://en.wiktionary.org/wiki/Template:attention
          // Ignore these.
        } else if (name.equals("infl") || name.equals("head")) {
          // See: http://en.wiktionary.org/wiki/Template:infl
          final String langCode = get(args, 0);
          String head = namedArgs.remove("head");
          if (head == null) {
            head = namedArgs.remove("title"); // Bug
          }
          if (head == null) {
            head = title;
          } else {
            head = WikiTokenizer.toPlainText(head);
          }
          titleAppended = true;
          
          namedArgs.keySet().removeAll(USELESS_WIKI_ARGS);
  
          final String tr = namedArgs.remove("tr");
          String g = namedArgs.remove("g");
          if (g == null) {
            g = namedArgs.remove("gender");
          }
          final String g2 = namedArgs.remove("g2");
          final String g3 = namedArgs.remove("g3");
  
          foreignBuilder.append(head);
    
          if (g != null) {
            foreignBuilder.append(" {").append(g);
            if (g2 != null) {
              foreignBuilder.append("|").append(g2);
            }
            if (g3 != null) {
              foreignBuilder.append("|").append(g3);
            }
            foreignBuilder.append("}");
          }
    
          if (tr != null) {
            foreignBuilder.append(String.format(TRANSLITERATION_FORMAT, tr));
            wordForms.add(tr);
          }
    
          final String pos = get(args, 1);
          if (pos != null) {
            foreignBuilder.append(" (").append(pos).append(")");
          }
          for (int i = 2; i < args.size(); i += 2) {
            final String inflName = get(args, i);
            final String inflValue = get(args, i + 1);
            foreignBuilder.append(", ").append(WikiTokenizer.toPlainText(inflName));
            if (inflValue != null && inflValue.length() > 0) {
              foreignBuilder.append(": ").append(WikiTokenizer.toPlainText(inflValue));
              wordForms.add(inflValue);
            }
          }
          for (final String key : namedArgs.keySet()) {
            final String value = WikiTokenizer.toPlainText(namedArgs.get(key));
            foreignBuilder.append(" ").append(key).append("=").append(value);
            wordForms.add(value);
          }
        } else if (name.equals("it-noun")) {
          titleAppended = true;
          final String base = get(args, 0);
          final String gender = get(args, 1);
          final String singular = base + get(args, 2);
          final String plural = base + get(args, 3);
          foreignBuilder.append(String.format(" %s {%s}, %s {pl}", singular, gender, plural, plural));
          wordForms.add(singular);
          wordForms.add(plural);
          if (!namedArgs.isEmpty() || args.size() > 4) {
            LOG.warning("Invalid it-noun: " + wikiTokenizer.token());
          }
        } else if (name.equals("it-proper noun")) {
          foreignBuilder.append(wikiTokenizer.token());
        } else if (name.equals("it-adj")) {
          foreignBuilder.append(wikiTokenizer.token());
        } else if (name.startsWith("it-conj")) {
          if (name.equals("it-conj-are")) {
            itConjAre(args, namedArgs);
          } else if (name.equals("it-conj-ere")) {
          } else if (name.equals("it-conj-ire")) {
          } else {
            LOG.warning("Unknown conjugation: " + wikiTokenizer.token());
          }
        } else {
          // Unindexed!
          foreignBuilder.append(wikiTokenizer.token());
          // LOG.warning("Unknown function: " + wikiTokenizer.token());
        }
      } else if (wikiTokenizer.isListItem()) {
        final String prefix = wikiTokenizer.listItemPrefix();
        if (lastListSection != null && 
            prefix.startsWith(lastListSection.firstPrefix) && 
            prefix.length() > lastListSection.firstPrefix.length()) {
          lastListSection.nextPrefixes.add(prefix);
          lastListSection.nextLines.add(wikiTokenizer.listItemWikiText());
        } else {
          lastListSection = new ListSection(prefix, wikiTokenizer.listItemWikiText());
          listSections.add(lastListSection);
        }
      } else if (lastListSection != null) {
        // Don't append anything after the lists, because there's crap.
      } else if (wikiTokenizer.isWikiLink()) {
        // Unindexed!
        foreignBuilder.append(wikiTokenizer.wikiLinkText());
        
      } else if (wikiTokenizer.isPlainText()) {
        // Unindexed!
        foreignBuilder.append(wikiTokenizer.token());
        
      } else if (wikiTokenizer.isMarkup() || wikiTokenizer.isNewline() || wikiTokenizer.isComment()) {
        // Do nothing.
      } else {
        LOG.warning("Unexpected token: " + wikiTokenizer.token());
      }
    }
    
    } finally {
      // Here's where we exit.
      // Should we make an entry even if there are no foreign list items?
      String foreign = foreignBuilder.toString().trim();
      if (!titleAppended && !foreign.toLowerCase().startsWith(title.toLowerCase())) {
        foreign = String.format("%s %s", title, foreign);
      }
      if (!langPattern.matcher(lang).matches()) {
        foreign = String.format("(%s) %s", lang, foreign);
      }
      for (final ListSection listSection : listSections) {
        doForeignListItem(foreign, title, wordForms, listSection);
      }
    }
  }
  
  
  static final Pattern UNINDEXED_WIKI_TEXT = Pattern.compile(
      "(first|second|third)-person (singular|plural)|" +
      "present tense|" +
      "imperative"
      );

  // Might only want to remove "lang" if it's equal to "zh", for example.
  static final Set<String> USELESS_WIKI_ARGS = new LinkedHashSet<String>(Arrays.asList("lang", "sc", "sort", "cat"));

  private void doForeignListItem(final String foreignText, String title, final Collection<String> forms, final ListSection listSection) {
    
    final String prefix = listSection.firstPrefix;
    if (prefix.length() > 1) {
      // Could just get looser and say that any prefix longer than first is a sublist.
      LOG.warning("Prefix too long: " + listSection);
      return;
    }
    
    final PairEntry pairEntry = new PairEntry();
    final IndexedEntry indexedEntry = new IndexedEntry(pairEntry);
    
    final StringBuilder englishBuilder = new StringBuilder();

    final String mainLine = listSection.firstLine;
    final WikiTokenizer englishTokenizer = new WikiTokenizer(mainLine, false);
    while (englishTokenizer.nextToken() != null) {
      // TODO handle form of....
      if (englishTokenizer.isPlainText()) {
        englishBuilder.append(englishTokenizer.token());
        enIndexBuilder.addEntryWithString(indexedEntry, englishTokenizer.token(), EntryTypeName.WIKTIONARY_ENGLISH_DEF);
      } else if (englishTokenizer.isWikiLink()) {
        final String text = englishTokenizer.wikiLinkText();
        final String link = englishTokenizer.wikiLinkDest();
        if (link != null) {
          if (link.contains("#English")) {
            englishBuilder.append(text);
            enIndexBuilder.addEntryWithString(indexedEntry, text, EntryTypeName.WIKTIONARY_ENGLISH_DEF_WIKI_LINK);
          } else if (link.contains("#") && this.langPattern.matcher(link).find()) {
            englishBuilder.append(text);
            foreignIndexBuilder.addEntryWithString(indexedEntry, text, EntryTypeName.WIKTIONARY_ENGLISH_DEF_OTHER_LANG);
          } else if (link.equals("plural")) {
            englishBuilder.append(text);
          } else {
            //LOG.warning("Special link: " + englishTokenizer.token());
            enIndexBuilder.addEntryWithString(indexedEntry, text, EntryTypeName.WIKTIONARY_ENGLISH_DEF_WIKI_LINK);
            englishBuilder.append(text);
          }
        } else {
          // link == null
          englishBuilder.append(text);
          if (!UNINDEXED_WIKI_TEXT.matcher(text).find()) {
            enIndexBuilder.addEntryWithString(indexedEntry, text, EntryTypeName.WIKTIONARY_ENGLISH_DEF_WIKI_LINK);
          }
        }
      } else if (englishTokenizer.isFunction()) {
        final String name = englishTokenizer.functionName();
        final List<String> args = englishTokenizer.functionPositionArgs();
        final Map<String,String> namedArgs = englishTokenizer.functionNamedArgs();
        
        if (
            name.equals("form of") || 
            name.contains("conjugation of") || 
            name.contains("participle of") || 
            name.contains("gerund of") || 
            name.contains("feminine of") || 
            name.contains("plural of")) {
          String formName = name;
          if (name.equals("form of")) {
            formName = remove(args, 0, null);
          }
          if (formName == null) {
            LOG.warning("Missing form name: " + title);
            formName = "form of";
          }
          String baseForm = get(args, 1, "");
          if ("".equals(baseForm)) {
            baseForm = get(args, 0, null);
            remove(args, 1, "");
          } else {
            remove(args, 0, null);
          }
          namedArgs.keySet().removeAll(USELESS_WIKI_ARGS);
          WikiTokenizer.appendFunction(englishBuilder.append("{"), formName, args, namedArgs).append("}");
          if (baseForm != null) {
            foreignIndexBuilder.addEntryWithString(indexedEntry, baseForm, EntryTypeName.WIKTIONARY_BASE_FORM_SINGLE, EntryTypeName.WIKTIONARY_BASE_FORM_MULTI);
          } else {
            // null baseForm happens in Danish.
            LOG.warning("Null baseform: " + title);
          }
        } else if (name.equals("l")) {
          // encodes text in various langs.
          // lang is arg 0.
          englishBuilder.append("").append(args.get(1));
          final String langCode = args.get(0);
          if ("en".equals(langCode)) {
            enIndexBuilder.addEntryWithString(indexedEntry, args.get(1), EntryTypeName.WIKTIONARY_ENGLISH_DEF_WIKI_LINK);
          } else {
            foreignIndexBuilder.addEntryWithString(indexedEntry, args.get(1), EntryTypeName.WIKTIONARY_ENGLISH_DEF_OTHER_LANG);
          }
          // TODO: transliteration
          
        } else if (name.equals("defn") || name.equals("rfdef")) {
          // Do nothing.
          // http://en.wiktionary.org/wiki/Wiktionary:Requests_for_deletion/Others#Template:defn
          // Redundant, used for the same purpose as {{rfdef}}, but this 
          // doesn't produce the "This word needs a definition" text. 
          // Delete or redirect.
        } else {
          namedArgs.keySet().removeAll(USELESS_WIKI_ARGS);
          if (args.size() == 0 && namedArgs.isEmpty()) {
            englishBuilder.append("{").append(name).append("}");
          } else {
            WikiTokenizer.appendFunction(englishBuilder.append("{{"), name, args, namedArgs).append("}}");
          }
//          LOG.warning("Unexpected function: " + englishTokenizer.token());
        }
      } else {
        if (englishTokenizer.isComment() || englishTokenizer.isMarkup()) {
        } else {
          LOG.warning("Unexpected definition type: " + englishTokenizer.token());
        }
      }
    }
        
    final String english = trim(englishBuilder.toString());
    if (english.length() > 0) {
      final Pair pair = new Pair(english, trim(foreignText), this.swap);
      pairEntry.pairs.add(pair);
      foreignIndexBuilder.addEntryWithString(indexedEntry, title, EntryTypeName.WIKTIONARY_TITLE_SINGLE, EntryTypeName.WIKTIONARY_TITLE_MULTI);
      for (final String form : forms) {
        foreignIndexBuilder.addEntryWithString(indexedEntry, form, EntryTypeName.WIKTIONARY_INFLECTD_FORM_SINGLE, EntryTypeName.WIKTIONARY_INFLECTED_FORM_MULTI);
      }
    }
    
    // Do examples.
    String lastForeign = null;
    for (int i = 0; i < listSection.nextPrefixes.size(); ++i) {
      final String nextPrefix = listSection.nextPrefixes.get(i);
      final String nextLine = listSection.nextLines.get(i);
      int dash = nextLine.indexOf("&mdash;");
      int mdashLen = 7;
      if (dash == -1) {
        dash = nextLine.indexOf("—");
        mdashLen = 1;
      }
      if (dash == -1) {
        dash = nextLine.indexOf(" - ");
        mdashLen = 3;
      }
      
      if ((nextPrefix.equals("#:") || nextPrefix.equals("##:")) && dash != -1) {
        final String foreignEx = nextLine.substring(0, dash);
        final String englishEx = nextLine.substring(dash + mdashLen);
        final Pair pair = new Pair(formatAndIndexExampleString(englishEx, enIndexBuilder, indexedEntry), formatAndIndexExampleString(foreignEx, foreignIndexBuilder, indexedEntry), swap);
        if (pair.lang1 != "--" && pair.lang1 != "--") {
          pairEntry.pairs.add(pair);
        }
        lastForeign = null;
      } else if (nextPrefix.equals("#:") || nextPrefix.equals("##:")){
        final Pair pair = new Pair("--", formatAndIndexExampleString(nextLine, null, indexedEntry), swap);
        lastForeign = nextLine;
        if (pair.lang1 != "--" && pair.lang1 != "--") {
          pairEntry.pairs.add(pair);
        }
      } else if (nextPrefix.equals("#::") || nextPrefix.equals("#**")) {
        if (lastForeign != null && pairEntry.pairs.size() > 0) {
          pairEntry.pairs.remove(pairEntry.pairs.size() - 1);
          final Pair pair = new Pair(formatAndIndexExampleString(nextLine, enIndexBuilder, indexedEntry), formatAndIndexExampleString(lastForeign, foreignIndexBuilder, indexedEntry), swap);
          if (pair.lang1 != "--" || pair.lang2 != "--") {
            pairEntry.pairs.add(pair);
          }
          lastForeign = null;
        } else {
          LOG.warning("TODO: English example with no foreign: " + title + ", " + nextLine);
          final Pair pair = new Pair("--", formatAndIndexExampleString(nextLine, null, indexedEntry), swap);
          if (pair.lang1 != "--" || pair.lang2 != "--") {
            pairEntry.pairs.add(pair);
          }
        }
      } else if (nextPrefix.equals("#*")) {
        // Can't really index these.
        final Pair pair = new Pair("--", formatAndIndexExampleString(nextLine, null, indexedEntry), swap);
        lastForeign = nextLine;
        if (pair.lang1 != "--" || pair.lang2 != "--") {
          pairEntry.pairs.add(pair);
        }
      } else if (nextPrefix.equals("#::*") || nextPrefix.equals("##") || nextPrefix.equals("#*:") || nextPrefix.equals("#:*") || true) {
        final Pair pair = new Pair("--", formatAndIndexExampleString(nextLine, null, indexedEntry), swap);
        if (pair.lang1 != "--" || pair.lang2 != "--") {
          pairEntry.pairs.add(pair);
        }
//      } else {
//        assert false;
      }
    }
  }
  
  private String formatAndIndexExampleString(final String example, final IndexBuilder indexBuilder, final IndexedEntry indexedEntry) {
    final WikiTokenizer wikiTokenizer = new WikiTokenizer(example, false);
    final StringBuilder builder = new StringBuilder();
    boolean insideTripleQuotes = false;
    while (wikiTokenizer.nextToken() != null) {
      if (wikiTokenizer.isPlainText()) {
        builder.append(wikiTokenizer.token());
        if (indexBuilder != null) {
          indexBuilder.addEntryWithString(indexedEntry, wikiTokenizer.token(), EntryTypeName.WIKTIONARY_EXAMPLE);
        }
      } else if (wikiTokenizer.isWikiLink()) {
        final String text = wikiTokenizer.wikiLinkText().replaceAll("'", ""); 
        builder.append(text);
        if (indexBuilder != null) {
          indexBuilder.addEntryWithString(indexedEntry, text, EntryTypeName.WIKTIONARY_EXAMPLE);
        }
      } else if (wikiTokenizer.isFunction()) {
        builder.append(wikiTokenizer.token());
      } else if (wikiTokenizer.isMarkup()) {
        if (wikiTokenizer.token().equals("'''")) {
          insideTripleQuotes = !insideTripleQuotes;
        }
      } else if (wikiTokenizer.isComment() || wikiTokenizer.isNewline()) {
        // Do nothing.
      } else {
        LOG.warning("unexpected token: " + wikiTokenizer.token());
      }
    }
    final String result = trim(builder.toString());
    return result.length() > 0 ? result : "--";
  }


  private void itConjAre(List<String> args, Map<String, String> namedArgs) {
    final String base = args.get(0);
    final String aux = args.get(1);
    
    putIfMissing(namedArgs, "inf", base + "are");
    putIfMissing(namedArgs, "aux", aux);
    putIfMissing(namedArgs, "ger", base + "ando");
    putIfMissing(namedArgs, "presp", base + "ante");
    putIfMissing(namedArgs, "pastp", base + "ato");
    // Present
    putIfMissing(namedArgs, "pres1s", base + "o");
    putIfMissing(namedArgs, "pres2s", base + "i");
    putIfMissing(namedArgs, "pres3s", base + "a");
    putIfMissing(namedArgs, "pres1p", base + "iamo");
    putIfMissing(namedArgs, "pres2p", base + "ate");
    putIfMissing(namedArgs, "pres3p", base + "ano");
    // Imperfect
    putIfMissing(namedArgs, "imperf1s", base + "avo");
    putIfMissing(namedArgs, "imperf2s", base + "avi");
    putIfMissing(namedArgs, "imperf3s", base + "ava");
    putIfMissing(namedArgs, "imperf1p", base + "avamo");
    putIfMissing(namedArgs, "imperf2p", base + "avate");
    putIfMissing(namedArgs, "imperf3p", base + "avano");
    // Passato remoto
    putIfMissing(namedArgs, "prem1s", base + "ai");
    putIfMissing(namedArgs, "prem2s", base + "asti");
    putIfMissing(namedArgs, "prem3s", base + "ò");
    putIfMissing(namedArgs, "prem1p", base + "ammo");
    putIfMissing(namedArgs, "prem2p", base + "aste");
    putIfMissing(namedArgs, "prem3p", base + "arono");
    // Future
    putIfMissing(namedArgs, "fut1s", base + "erò");
    putIfMissing(namedArgs, "fut2s", base + "erai");
    putIfMissing(namedArgs, "fut3s", base + "erà");
    putIfMissing(namedArgs, "fut1p", base + "eremo");
    putIfMissing(namedArgs, "fut2p", base + "erete");
    putIfMissing(namedArgs, "fut3p", base + "eranno");
    // Conditional
    putIfMissing(namedArgs, "cond1s", base + "erei");
    putIfMissing(namedArgs, "cond2s", base + "eresti");
    putIfMissing(namedArgs, "cond3s", base + "erebbe");
    putIfMissing(namedArgs, "cond1p", base + "eremmo");
    putIfMissing(namedArgs, "cond2p", base + "ereste");
    putIfMissing(namedArgs, "cond3p", base + "erebbero");
    // Subjunctive / congiuntivo
    putIfMissing(namedArgs, "sub123s", base + "i");
    putIfMissing(namedArgs, "sub1p", base + "iamo");
    putIfMissing(namedArgs, "sub2p", base + "iate");
    putIfMissing(namedArgs, "sub3p", base + "ino");
    // Imperfect subjunctive
    putIfMissing(namedArgs, "impsub12s", base + "assi");
    putIfMissing(namedArgs, "impsub3s", base + "asse");
    putIfMissing(namedArgs, "impsub1p", base + "assimo");
    putIfMissing(namedArgs, "impsub2p", base + "aste");
    putIfMissing(namedArgs, "impsub3p", base + "assero");
    // Imperative
    putIfMissing(namedArgs, "imp2s", base + "a");
    putIfMissing(namedArgs, "imp3s", base + "i");
    putIfMissing(namedArgs, "imp1p", base + "iamo");
    putIfMissing(namedArgs, "imp2p", base + "ate");
    putIfMissing(namedArgs, "imp3p", base + "ino");


    itConj(args, namedArgs);
  }


  private void itConj(List<String> args, Map<String, String> namedArgs) {
    // TODO Auto-generated method stub
    
  }


  private static void putIfMissing(final Map<String, String> namedArgs, final String key,
      final String value) {
    final String oldValue = namedArgs.get(key);
    if (oldValue == null || oldValue.length() == 0) {
      namedArgs.put(key, value);
    }
  }
  
  // TODO: check how ='' and =| are manifested....
  // TODO: get this right in -are
  private static void putOrNullify(final Map<String, String> namedArgs, final String key,
      final String value) {
    final String oldValue = namedArgs.get(key);
    if (oldValue == null/* || oldValue.length() == 0*/) {
      namedArgs.put(key, value);
    } else {
      if (oldValue.equals("''")) {
        namedArgs.put(key, "");
      }
    }
  }

  static final Pattern whitespace = Pattern.compile("\\s+");
  static String trim(final String s) {
    return whitespace.matcher(s).replaceAll(" ").trim();
  }

  
}
