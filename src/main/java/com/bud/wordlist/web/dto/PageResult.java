package com.bud.wordlist.web.dto;

import java.util.List;

public record PageResult<T>(long total, int page, int size, List<T> list) {
}
