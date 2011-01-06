package org.fao.geonet.kernel.search.log;

import jeeves.resources.dbms.Dbms;
import jeeves.server.context.ServiceContext;
import jeeves.utils.Log;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.FuzzyQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.NumericRangeQuery;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TermRangeQuery;
import org.apache.lucene.search.WildcardQuery;
import org.fao.geonet.GeonetContext;
import org.fao.geonet.constants.Geonet;
import org.jdom.Element;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Vector;

/**
 * A class to log Lucene search queries (context, search parameters and search
 * results); this class is built with the GeonetContext object. 
 * If the settings has true for the value: searchStats/enable, log operations 
 * will be performed, otherwise nothing will be done and a notice-level log 
 * will be made
 * 
 * @author nicolas Ribot
 * 
 */
public class SearcherLogger {
	private ServiceContext srvContext;
	/** To access the GeonetContext.SettingManager value for searchStats/enable 
	 * key. If false, no log will be done
	 */
	private boolean isEnabled;
    private boolean logSpatial;
    private List<String> luceneTermsToExclude;

	public SearcherLogger(ServiceContext srvContext, boolean logSpatial, String luceneTermsList) {
		// TODO Auto-generated constructor stub
		this.srvContext = srvContext;
		this.isEnabled = false;
        this.logSpatial = logSpatial;

        this.luceneTermsToExclude = Arrays.asList(luceneTermsList.split(","));
		
		if (srvContext == null) { // todo: handle exception/errors
			Log.warning(Geonet.SEARCH_LOGGER, "null serviceContext object. will not be able to log queries...");
		} else {
			GeonetContext gc = (GeonetContext) this.srvContext.getHandlerContext(Geonet.CONTEXT_NAME);
			Element logEnableElm = gc.getSettingManager().get("system/searchStats/enable", 0);
			String val = logEnableElm != null ? logEnableElm.getValue() : "false";
			this.isEnabled = (val != null && "true".equalsIgnoreCase(val));
		}
		if (! this.isEnabled) {
			Log.warning(Geonet.SEARCH_LOGGER, "Search Log is disabled. See administration page to enable it.");
		}
    Log.debug(Geonet.SEARCH_LOGGER, "SearcherLogger created. Spatial object logging ? " + this.logSpatial + ". lucene terms to exclude from log: " + luceneTermsList);
	}

	public void logSearch(Query query, int numHits, Sort sort, String geomFilterWKT, String guiService) {
		if (!isEnabled) {
			return;
		}
		try{
    		Dbms dbms = (Dbms) srvContext.getResourceManager().open(Geonet.Res.MAIN_DB);
    		if (dbms == null) {
    			Log.debug(Geonet.SEARCH_LOGGER,
    					"Null Dbms object. cannot log search operation");
    			return;
    		}
    
    		if (query == null) {
    			Log.debug(Geonet.SEARCH_LOGGER,
    					"Null Query object. cannot log search operation");
    			return;
    		}
    		
    		QueryRequest queryRequest = new QueryRequest(srvContext.getIpAddress(), (new java.util.Date()).getTime());
    		Vector<QueryInfo> queryInfos = extractQueryTerms(query);
    		// type is also set when doing this.
    		queryRequest.setQueryInfos(queryInfos);
    		queryRequest.setHits(numHits);
    		queryRequest.setService(this.srvContext.getService());
    		queryRequest.setLanguage(this.srvContext.getLanguage());
    		queryRequest.setLuceneQuery(query.toString());
    		// sortBy, spatial filter ?
    		if (sort != null) queryRequest.setSortBy(concatSortFields(sort.getSort()));
    		// todo: use filter to extract geom from
    		queryRequest.setSpatialFilter(geomFilterWKT);
    		// sets the simple type through this call...
    		queryRequest.isSimpleQuery();
    		queryRequest.setAutoGeneratedQuery(guiService.equals("yes"));
    		
    		if (!queryRequest.storeToDb(dbms, srvContext.getSerialFactory())) {
    			Log.warning(Geonet.SEARCH_LOGGER, "unable to log query into database...");
    		} else {
    			Log.debug(Geonet.SEARCH_LOGGER, "Query saved to database");
    		}
    		
    		// debug only
    		/*
    		for (QueryInfo q : queryInfos) {
    			if (q != null) {
    				System.out.println(q.toString());
    			} else {
    				System.out.println("null queryInfo object");
    			}
    		}
    		*/
		}catch (Exception e) {
            // I dont want the log to cause an exception and hide the real problem.
		    Log.error(Geonet.SEARCH_LOGGER, "Error logging search: "+e.getMessage());
            e.printStackTrace(); //fixme should be removed after control.
        }
	}
	
	/** Returns a dictionary containing field/text for the given query
	 *  
	 * @param query The query to process to extract terms and 
	 * @return a Hashtable whose key is the field and the value is the text
	 */
	protected Vector<QueryInfo> extractQueryTerms(Query query) {
		if (query == null) {
			return null;
		}
		Vector<QueryInfo> result = new Vector<QueryInfo>();
		BooleanClause[] clauses = null;
		QueryInfo qInfo = null;
		if (query instanceof BooleanQuery) {
			BooleanQuery bq = (BooleanQuery) query;
			clauses = bq.getClauses();
			
			for (BooleanClause clause : clauses) {
				result.addAll(extractQueryTerms(clause.getQuery()));
			}
		} else if (query instanceof TermQuery) {
			qInfo = new QueryInfo(((TermQuery)query).getTerm().field(),
					((TermQuery)query).getTerm().text(),
					QueryInfo.TERM_QUERY);
			
		} else if (query instanceof FuzzyQuery) {
			qInfo = new QueryInfo(((FuzzyQuery)query).getTerm().field(),
					((FuzzyQuery)query).getTerm().text(),
					QueryInfo.FUZZY_QUERY);
			qInfo.setSimilarity(((FuzzyQuery)query).getMinSimilarity());
		} else if (query instanceof PrefixQuery) {
			qInfo = new QueryInfo(((PrefixQuery)query).getPrefix().field(),
					((PrefixQuery)query).getPrefix().text(),
					QueryInfo.PREFIX_QUERY);
		} else if (query instanceof MatchAllDocsQuery) {
			// extract all terms for this query (text and field)
			HashSet<Term> terms = new HashSet<Term>();
			((MatchAllDocsQuery)query).extractTerms(terms);
			// one should consider that all terms refer to the same field, or not ?
			String fields = concatTermsField(terms.toArray(new Term[terms.size()]), null);
			String texts = concatTermsText(new Term[terms.size()], null);
			qInfo = new QueryInfo(fields,
					texts,
					QueryInfo.MATCH_ALL_DOCS_QUERY);
		} else if (query instanceof WildcardQuery) {
			qInfo = new QueryInfo(((WildcardQuery)query).getTerm().field(),
					((WildcardQuery)query).getTerm().text(),
					QueryInfo.WILDCARD_QUERY);
		} else if (query instanceof PhraseQuery) {
			// extract all terms for this query (text and field)
			Term[] terms = ((PhraseQuery)query).getTerms();
			String fields = concatTermsField(terms, null);
			String texts = concatTermsText(terms, null);
			qInfo = new QueryInfo(fields,
					texts,
					QueryInfo.PHRASE_QUERY);
		} else if (query instanceof TermRangeQuery) {
			qInfo = new QueryInfo(QueryInfo.RANGE_QUERY);
			qInfo.setLowerText(((TermRangeQuery)query).getLowerTerm());
			qInfo.setUpperText(((TermRangeQuery)query).getUpperTerm());
			// we consider that all terms are comming from the same field for this query
			qInfo.setField(((TermRangeQuery)query).getField());
		} else if (query instanceof NumericRangeQuery) {
			qInfo = new QueryInfo(QueryInfo.NUMERIC_RANGE_QUERY);
			qInfo.setLowerText(((NumericRangeQuery<?>)query).getMin().toString());
			qInfo.setUpperText(((NumericRangeQuery<?>)query).getMax().toString());
			qInfo.setField(((NumericRangeQuery<?>)query).getField());
		} else {
			Log.warning(Geonet.SEARCH_LOGGER, "unknown queryInfo type: " + query.getClass().getName());
		}

		if (qInfo != null && !this.luceneTermsToExclude.contains(qInfo.getField())) {
			result.add(qInfo);
		} else if (qInfo != null) {
    		//Log.debug(Geonet.SEARCH_LOGGER, "excluding this term from log: " + qInfo.getField() + " - " + qInfo.getText());
        }
		return result;
	}
	
	/**
	 * concatenates the given terms' text into a single String, with the given separator
	 * 
	 * @param terms the set of terms to concatenate
	 * @param separator the separator to use to separate text elements (use ',' if sep is null)
	 * @return  a string containing all this terms' texts concatenated
	 */
	private String concatTermsText(Term[] terms, String separator) {
		if (terms == null || separator == null) return null;

		String sep = separator == null ? "," : separator;
		StringBuilder sb = new StringBuilder();
		for (Term t : terms) {
			sb.append(t.text()).append(sep);
		}
		if (sb.length() > 0) {
			sb.deleteCharAt(sb.length()-1);
		}
		return sb.toString();
	}
	
	/**
	 * concatenates the given terms' fields into a single String, with the given separator
	 * 
	 * @param terms the set of terms to concatenate
	 * @param separator the separator to use to separate text elements  (use ',' if sep is null)
	 * @return a string containing all this terms' fields concatenated
	 */
	private String concatTermsField(Term[] terms, String separator) {
		if (terms == null || separator == null) return null;

		String sep = separator == null ? "," : separator;
		StringBuilder sb = new StringBuilder();
		for (Term t : terms) {
			sb.append(t.field()).append(sep);
		}
		if (sb.length() > 0) {
			sb.deleteCharAt(sb.length()-1);
		}
		return sb.toString();
	}
	
	/**
	 * Concatenates the given sortFields into a single string of the form
	 * The concatenation will lead to a string:
	 * <field> ASC|DESC,<field> ASC|DESC,...
	 * where <field> is the sort field name and ASC means ascending sort; DESC means descending sort
	 * (reverse sort)
	 * @param sortFields the array of fields to concatenate
	 * @return a string containing fields concatenated.
	 */
	private String concatSortFields(SortField[] sortFields) {
		if (sortFields == null) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		for (SortField sf : sortFields) {
			if (sf != null) {
				sb.append(sf.getField()).append(" ").append(sf.getReverse() ? "DESC" : "ASC").append(",");
			}
		}
		if (sb.length() > 0) {
			sb.deleteCharAt(sb.length()-1);
		}
		return sb.toString();
	}
}

