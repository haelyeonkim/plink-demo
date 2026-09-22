package com.plink.ticket.model;

/** A place inside the event that hands something over: a bar, a stand, a cloakroom. */
public class Booth {
    public long id, sessionId;
    public String name, note;
    public java.sql.Timestamp createdAt;
}
