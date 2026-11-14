package com.toadzip.backend.announcement.service;

/**
 * 관리자 보정이 공급표 개체의 관리자 수정 표시(adminModified)를 정하는 규칙(결정 4·33). 주택형·공급대상·수량 묶음·임대조건에 같게 쓴다.
 */
public enum SupplySheetProtection {
    /** 공급정보 편집표: 새 개체, 이미 관리자가 고친 개체, 저장값과 다른 개체는 관리자 수정으로 남긴다. */
    WORKBENCH,
    /** PDF 보정: 관리자가 고친 값과 PDF 값이 같을 때만 표시를 남긴다. PDF 값으로 덮거나 새로 만든 개체는 표시를 지운다. */
    PDF;

    /** stored는 저장된 개체에 맞춰졌는지, sameValue는 그 저장값과 요청 값이 같은지다. */
    boolean adminModified(boolean stored, boolean storedAdminModified, boolean sameValue) {
        if (this == WORKBENCH) {
            return !stored || storedAdminModified || !sameValue;
        }
        return stored && storedAdminModified && sameValue;
    }
}
