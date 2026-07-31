package com.semojum.backend.domain.org.repository;

import com.semojum.backend.domain.org.entity.Organization;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {
}
