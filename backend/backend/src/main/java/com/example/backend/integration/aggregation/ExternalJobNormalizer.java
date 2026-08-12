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
 static String canonicalUrl(String value){ try { String safe=SafeExternalUrl.parse(value).orElse(null); if(safe==null)return null; URI u=new URI(safe); String path=u.getPath()==null?"":u.getPath().replaceAll("/$",""); return new URI(u.getScheme().toLowerCase(Locale.ROOT),null,u.getHost().toLowerCase(Locale.ROOT),u.getPort(),path.isEmpty()?"/":path,null,null).toASCIIString(); }catch(Exception e){return null;} }
 static String sanitize(String html){ return html == null ? null : text(org.jsoup.Jsoup.parse(html).text()); }
 static LocalDateTime parseDate(String value){ if(value==null||value.isBlank())return null; try{return OffsetDateTime.parse(value).toLocalDateTime();}catch(DateTimeParseException e){try{return LocalDate.parse(value).atStartOfDay();}catch(DateTimeParseException ignored){return null;}} }
 static String fingerprint(String c,String t,String l,String u){ if(c==null||t==null||u==null)return null; try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((c+"|"+t+"|"+(l==null?"":l)+"|"+u).toLowerCase(Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);} }
}
