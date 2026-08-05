package com.semojum.backend.domain.folder.service;

import com.semojum.backend.domain.folder.entity.Folder;
import com.semojum.backend.domain.folder.repository.FolderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 폴더의 "수정한 날짜"를 갱신하는 공통 지점.
 *
 * <p>폴더 안 항목이 바뀌는 곳이 폴더·작업·휴지통·편집 서비스에 흩어져 있어, 갱신 규칙을
 * 한곳에 모아 둔다. 호출부는 "무엇이 바뀐 폴더인가"만 넘기면 된다.
 *
 * <p>루트(folderId가 null)는 폴더 실체가 없으므로 아무 일도 하지 않는다.
 * 휴지통에 있는 폴더도 갱신하지 않는다 — 목록에 나오지 않아 의미가 없다.
 */
@Component
@RequiredArgsConstructor
public class FolderTouch {

    private final FolderRepository folderRepository;

    /** 직속 항목이 바뀐 폴더 하나를 갱신한다. */
    public void touch(UUID userId, UUID folderId) {
        if (folderId == null) return;
        folderRepository.findActiveByIdAndUserId(folderId, userId).ifPresent(Folder::touchModified);
    }

    /** 여러 폴더를 갱신한다(중복·null은 알아서 걸러낸다). */
    public void touchAll(UUID userId, Collection<UUID> folderIds) {
        Set<UUID> unique = new HashSet<>(folderIds);
        unique.remove(null);
        for (UUID id : unique) {
            touch(userId, id);
        }
    }
}
