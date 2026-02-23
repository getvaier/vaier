package com.wireweave.application;

public interface DeleteDnsRecordUseCase {

    void deleteDnsRecord(String recordName, String recordType, String zoneName);
}
