package com.example.demo.iface.dto;

import com.example.demo.application.shared.projection.AccountQueriedProjection;

public record AccountQueriedResource(String code, String message, AccountQueriedProjection data) {

}
