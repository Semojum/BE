package com.semojum.backend.domain.result.repository;

import com.semojum.backend.domain.result.entity.TextElement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TextElementRepository extends JpaRepository<TextElement, UUID> {}
