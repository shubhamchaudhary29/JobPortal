package com.example.backend.integration.aggregation;
import com.example.backend.integration.jobs.ExternalJob;
import com.example.backend.shared.validation.SafeExternalUrl;
import java.net.URI;
import java.text.Normalizer;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.Locale;
public final class ExternalJobNormalizer {
 private ExternalJobNormalizer() {}
 public static ExternalJob normalize(String id,String title,String description,String company,String location,String type,String url,String date) {
  return normalize(id,title,description,company,location,type,url,date,null,null);
 }
 /** Salary bounds are optional and never part of the fingerprint. */
 public static ExternalJob normalize(String id,String title,String description,String company,String location,String type,String url,String date,Double salaryMin,Double salaryMax) {
  String canonical=canonicalUrl(url); String t=text(title); String c=text(company); String l=location(location);
  return new ExternalJob(id, t, sanitize(description), c, l, text(type), salaryMin,salaryMax,canonical,parseDate(date),null, fingerprint(c,t,l,canonical));
 }
 static String text(String v){ return v==null?null:Normalizer.normalize(v.replaceAll("\\s+"," ").trim(),Normalizer.Form.NFKC); }
 /** "remote" alone stays "Remote"; "remote - x", "remote, x", and "remote x" become "Remote - x". */
 static String location(String v){ String value=text(v); if(value==null)return null; if(value.matches("(?i)^remote$"))return "Remote"; return value.replaceAll("(?i)^remote(?:\\s*[-,]\\s*|\\s+)(?=\\S)", "Remote - "); }
 /**
  * Validate a link without rewriting it.  Application URLs frequently contain signed
  * or tracking query parameters which are part of the employer's application flow.
  */
 static String canonicalUrl(String value){
  try { return SafeExternalUrl.parse(value).orElse(null); } catch (RuntimeException e) { return null; }
 }
 static String sanitize(String html){ return html == null ? null : text(org.jsoup.Jsoup.parse(html).text()); }
 static LocalDateTime parseDate(String value){ if(value==null||value.isBlank())return null; try{return OffsetDateTime.parse(value).toLocalDateTime();}catch(DateTimeParseException e){try{return LocalDateTime.parse(value);}catch(DateTimeParseException local){try{return LocalDate.parse(value).atStartOfDay();}catch(DateTimeParseException ignored){return null;}}} }
 static String fingerprint(String c,String t,String l,String ignoredUrl){ return JobFingerprint.of(c,t,l); }
}
