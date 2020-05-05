package com.axway.apim;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.http.NameValuePair;
import org.apache.http.message.BasicNameValuePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.axway.apim.adapter.APIManagerAdapter;
import com.axway.apim.adapter.APIMgrProxiesAdapter;
import com.axway.apim.api.IAPI;
import com.axway.apim.apiimport.APIImportConfigAdapter;
import com.axway.apim.apiimport.APIImportManager;
import com.axway.apim.apiimport.ActualAPI;
import com.axway.apim.apiimport.lib.APIImportCLIOptions;
import com.axway.apim.apiimport.lib.APIImportParams;
import com.axway.apim.apiimport.rollback.RollbackHandler;
import com.axway.apim.apiimport.state.APIChangeState;
import com.axway.apim.lib.APIMCLIServiceProvider;
import com.axway.apim.lib.APIPropertiesExport;
import com.axway.apim.lib.errorHandling.AppException;
import com.axway.apim.lib.errorHandling.ErrorCode;
import com.axway.apim.lib.errorHandling.ErrorCodeMapper;
import com.axway.apim.lib.errorHandling.ErrorState;
import com.axway.apim.lib.utils.rest.APIMHttpClient;
import com.axway.apim.lib.utils.rest.Transaction;

/**
 * This is the Entry-Point of program and responsible to:  
 * - read the command-line parameters to create a <code>CommandParameters</code>
 * - next is to read the API-Contract by creating an <code>APIImportConfig</code> instance and calling getImportAPIDefinition()
 * - the <code>APIManagerAdapter</code> method: <code>getAPIManagerAPI()</code> is used to create the API-Manager API state
 * - An <code>APIChangeState</code> is created based on ImportAPI and API-Manager API
 * - Finally the APIManagerAdapter:applyChanges() is called to replicate the state into the APIManager.   
 * 
 * @author cwiechmann@axway.com
 */
public class APIImportApp implements APIMCLIServiceProvider {

	private static Logger LOG = LoggerFactory.getLogger(APIImportApp.class);

	public static void main(String args[]) { 
		int rc = run(args);
		System.exit(rc);
	}
		
	public static int run(String args[]) {
		ErrorCodeMapper errorCodeMapper = new ErrorCodeMapper();
		try {
			
			// We need to clean some Singleton-Instances, as tests are running in the same JVM
			APIManagerAdapter.deleteInstance();
			ErrorState.deleteInstance();
			APIMHttpClient.deleteInstance();
			Transaction.deleteInstance();
			RollbackHandler.deleteInstance();
			
			APIImportParams params = new APIImportParams(new APIImportCLIOptions(args));
			errorCodeMapper.setMapConfiguration(params.getValue("returnCodeMapping"));
			
			APIManagerAdapter apimAdapter = APIManagerAdapter.getInstance();
			
			APIImportConfigAdapter configAdapter = new APIImportConfigAdapter(params.getValue("contract"), 
					params.getValue("stage"), params.getValue("apidefinition"), apimAdapter.isUsingOrgAdmin());
			// Creates an API-Representation of the desired API
			IAPI desiredAPI = configAdapter.getDesiredAPI();
			// 
			List<NameValuePair> filters = new ArrayList<NameValuePair>();
			// If we don't have an AdminAccount available, we ignore published APIs - For OrgAdmins 
			// the unpublished or pending APIs become the actual API
			if(!APIManagerAdapter.hasAdminAccount()) {
				filters.add(new BasicNameValuePair("field", "state"));
				filters.add(new BasicNameValuePair("op", "ne"));
				filters.add(new BasicNameValuePair("value", "published"));
			}
			// Lookup an existing APIs - If found the actualAPI is valid - desiredAPI is used to control what needs to be loaded
			IAPI actualAPI = apimAdapter.getAPIManagerAPI(new APIMgrProxiesAdapter.Builder(APIManagerAdapter.TYPE_FRONT_END)
					.hasApiPath(desiredAPI.getPath())
					.hasVHost(desiredAPI.getVhost())
					.hasQueryStringVersion(desiredAPI.getApiRoutingKey())
					.useFilter(filters)
					.build().getAPI(true), desiredAPI, ActualAPI.class);
			// Based on the actual API - fulfill/complete some elements in the desired API
			configAdapter.completeDesiredAPI(desiredAPI, actualAPI);
			APIChangeState changeActions = new APIChangeState(actualAPI, desiredAPI);
			new APIImportManager().applyChanges(changeActions);
			APIPropertiesExport.getInstance().store();
			LOG.info("Successfully replicated API-State into API-Manager");
			return 0;
		} catch (AppException ap) {
			APIPropertiesExport.getInstance().store(); // Try to create it, even 
			ErrorState errorState = ErrorState.getInstance();
			if(!ap.getErrorCode().equals(ErrorCode.NO_CHANGE)) {
				RollbackHandler rollback = RollbackHandler.getInstance();
				rollback.executeRollback();
			}
			if(errorState.hasError()) {
				errorState.logErrorMessages(LOG);
				if(errorState.isLogStackTrace()) LOG.error(ap.getMessage(), ap);
				return errorCodeMapper.getMapedErrorCode(errorState.getErrorCode()).getCode();
			} else {
				LOG.error(ap.getMessage(), ap);
				return errorCodeMapper.getMapedErrorCode(ap.getErrorCode()).getCode();
			}
		} catch (Exception e) {
			LOG.error(e.getMessage(), e);
			return ErrorCode.UNXPECTED_ERROR.getCode();
		}
	}

	@Override
	public String getId() {
		return "api";
	}
	
	@Override
	public String getVersion() {
		return APIImportApp.class.getPackage().getImplementationVersion();
	}

	@Override
	public String getMethod() {
		return "import";
	}

	@Override
	public String getDescription() {
		return "Import APIs into the API-Manager";
	}
	
	public String getName() {
		return "API Import";
	}

	@Override
	public int execute(String[] args) {
		return run(args);
	}
}
