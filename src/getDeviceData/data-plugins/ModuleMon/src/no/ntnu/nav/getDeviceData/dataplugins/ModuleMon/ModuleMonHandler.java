package no.ntnu.nav.getDeviceData.dataplugins.ModuleMon;

import java.util.*;
import java.sql.*;

import no.ntnu.nav.logger.*;
import no.ntnu.nav.Database.*;
import no.ntnu.nav.util.*;
import no.ntnu.nav.event.*;
import no.ntnu.nav.getDeviceData.Netbox;
import no.ntnu.nav.getDeviceData.dataplugins.*;

/**
 * DataHandler plugin for getDeviceData; provides an interface for storing
 * info about which modules of a switch/router is responding to requests.
 *
 * @see ModuleMonContainer
 */

public class ModuleMonHandler implements DataHandler {

	private static boolean DEBUG = false;

	private static Map moduleMap;
	private static Map modidMap;
	private static MultiMap modules;
	
	private static MultiMap queryIfindices;
	private static Map moduleToIfindex;

	private static Set modulesDown = new HashSet();
	

	/**
	 * Fetch initial data from swport table.
	 */
	public synchronized void init(Map persistentStorage, Map changedDeviceids) {
		boolean onlyUpdate = true;
		for (Iterator it = changedDeviceids.values().iterator(); it.hasNext() && onlyUpdate;) {
			if (((Integer)it.next()).intValue() != DataHandler.DEVICE_UPDATED) onlyUpdate = false;
		}

		if (persistentStorage.containsKey("initDone") && (changedDeviceids.isEmpty() || onlyUpdate)) return;
		persistentStorage.put("initDone", null);

		Log.setDefaultSubsystem("ModuleMonHandler");

		synchronized (modulesDown) {
			int oldcnt = modidMap != null ? modidMap.size() : 0;

			try {
				// We need to the mapping from netboxid:ifindex -> module and the modules belonging to each netbox
				Map moduleMapL = Collections.synchronizedMap(new HashMap());
				Map modidMapL = Collections.synchronizedMap(new HashMap());
				MultiMap modulesL = new HashMultiMap();
				MultiMap queryIfindicesL = new HashMultiMap();
				Map moduleToIfindexL = Collections.synchronizedMap(new HashMap());
				Set queryDupe = new HashSet();
				ResultSet rs = Database.query("SELECT deviceid,netboxid,ifindex,moduleid,module FROM module JOIN swport USING(moduleid) ORDER BY RANDOM()");
				while (rs.next()) {
					String netboxid = rs.getString("netboxid");
					moduleMapL.put(rs.getString("netboxid")+":"+rs.getString("module"), rs.getString("moduleid"));
					modidMapL.put(rs.getString("moduleid"), rs.getString("deviceid"));
					modulesL.put(rs.getString("netboxid"), rs.getString("moduleid"));

					String k = rs.getString("netboxid")+":"+rs.getString("moduleid");
					if (queryDupe.add(k)) {
						queryIfindicesL.put(rs.getString("netboxid"), new String[] { rs.getString("ifindex"), rs.getString("module") });

						Map mm;
						if ( (mm=(Map)moduleToIfindexL.get(netboxid)) == null) moduleToIfindexL.put(netboxid, mm = new HashMap());
						mm.put(rs.getString("module"), rs.getString("ifindex"));
					}
				}

				rs = Database.query("SELECT netboxid,moduleid FROM module WHERE up='n'");
				while (rs.next()) {
					String key = rs.getString("netboxid")+":"+rs.getString("moduleid");
					modulesDown.add(key);
				}

				modidMap = modidMapL;
				moduleMap = moduleMapL;
				modules = modulesL;
				queryIfindices = queryIfindicesL;
				moduleToIfindex = moduleToIfindexL;
				Log.d("INIT", "Fetched " + modidMap.size() + " modules (" + (modidMap.size()-oldcnt) + " new)");
				if ((modidMap.size()-oldcnt) == 0) Log.w("INIT", "No new modules, changed: " + changedDeviceids);

			} catch (SQLException e) {
				Log.e("INIT", "SQLException: " + e.getMessage());
				e.printStackTrace(System.err);
			}
		}

	}

	/**
	 * Return a DataContainer object used to return data to this
	 * DataHandler.
	 */
	public DataContainer dataContainerFactory() {
		return new ModuleMonContainer(this, queryIfindices, moduleToIfindex);
	}
	
	/**
	 * Store the data in the DataContainer in the database.
	 */
	public void handleData(Netbox nb, DataContainer dc, Map changedDeviceids) {
		if (!(dc instanceof ModuleMonContainer)) return;
		ModuleMonContainer mmc = (ModuleMonContainer)dc;
		if (!mmc.isCommited()) return;

		synchronized (modulesDown) {
			if (DEBUG) err("Check modules down("+nb+"): " + modulesDown);

			Set mod = modules.get(nb.getNetboxidS());
			if (mod == null) {
				Log.w("MODULE_MON", "HANDLE", "No modules found for netbox " + nb.getSysname());
				return;
			}

			// Local copy we can modify
			mod = new HashSet(mod);
			if (DEBUG) err("  Modules: " + new ArrayList(mod));

			int severity = 50;
			if (DEBUG) err("  Modules up: " + mmc.getModulesUpSet());
			for (Iterator it = mmc.getModulesUp(); it.hasNext();) {
				String moduleid = (String)moduleMap.get(nb.getNetboxid()+":"+it.next());;
				String deviceid = (String)modidMap.get(moduleid);
				String key = nb.getNetboxid()+":"+moduleid;

				if (modulesDown.contains(key)) {
					// The module is coming up, send up event
					sendEvent(nb, deviceid, moduleid, Event.STATE_END, severity);
					modulesDown.remove(key);
				}
				mod.remove(moduleid);
			}

			// All remaining modules are now considered down; send event
			if (DEBUG) err("  Remaining modules: " + new ArrayList(mod));
			if (!mod.isEmpty()) Log.d("MODULE_MON", "REPORT_DOWN", "Reporting modules down: " + mod + " ("+modules.get(nb.getNetboxidS())+")");
			for (Iterator it = mod.iterator(); it.hasNext();) {
				String moduleid = (String)it.next();
				String deviceid = (String)modidMap.get(moduleid);

				Log.d("MODULE_MON", "REPORT_DOWN", "Module("+moduleid+","+deviceid+") is down, sending event");
				sendEvent(nb, deviceid, moduleid, Event.STATE_START, severity);
				modulesDown.add(nb.getNetboxid()+":"+moduleid);
			}
		}

	}

	// Post the event
	private void sendEvent(Netbox nb, String deviceid, String moduleid, int state, int severity) {
		if (!EventQ.createAndPostEvent("moduleMon", "eventEngine", Integer.parseInt(deviceid), nb.getNetboxid(), Integer.parseInt(moduleid), "moduleState", state, -1, severity, null)) {
			Log.c("MODULE_MON", "SEND_EVENT", "Error sending moduleUp|Down event for " + nb + ", moduleid: " + moduleid);
		}
	}

	private static void err(String s) {
		System.err.println(s);
	}

}
