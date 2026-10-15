package com.bud.wordlist.generation;

public record WordCard(
        String meaning,
        String phonetic,
        String pos,
        String mnemonic,
        String example,
        String phrase) {
}
