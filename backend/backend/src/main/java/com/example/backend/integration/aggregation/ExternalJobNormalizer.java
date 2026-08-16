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
  String canonical=canonicalUrl(url); String t=text(title); String c=text(company); String l=location(location);
  return new ExternalJob(id, t, sanitize(description), c, l, text(type), null,null,canonical,parseDate(date),null, fingerprint(c,t,l,canonical));
 }
 static String text(String v){ return v==null?null:Normalizer.normalize(v.replaceAll("\\s+"," ").trim(),Normalizer.Form.NFKC); }
 static String location(String v){ String value=text(v); return value==null?null:value.replaceAll("(?i)^remote\\s*[-,]?\\s*", "Remote - ").replaceAll("(?i)^remote$", "Remote"); }
 /**
  * Validate a link without rewriting it.  Application URLs frequently contain signed
  * or tracking query parameters which are part of the employer's application flow.
  */
 static String canonicalUrl(String value){
  try { return SafeExternalUrl.parse(value).orElse(null); } catch (RuntimeException e) { return null; }
 }
 static String sanitize(String html){ return html == null ? null : text(org.jsoup.Jsoup.parse(html).text()); }
 static LocalDateTime parseDate(String value){ if(value==null||value.isBlank())return null; try{return OffsetDateTime.parse(value).toLocalDateTime();}catch(DateTimeParseException e){try{return LocalDate.parse(value).atStartOfDay();}catch(DateTimeParseException ignored){return null;}} }
 static String fingerprint(String c,String t,String l,String ignoredUrl){
  if(c==null||t==null)return null;
  try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((c+"|"+t+"|"+(l==null?"":l)).toLowerCase(Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
 }
}
