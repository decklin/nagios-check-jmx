package org.nagios;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;
import java.util.Collections;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;


/**
 *
 * JMXQuery is used for local or remote request of JMX attributes
 * It requires JRE 1.5 to be used for compilation and execution.
 * Look method main for description how it can be invoked.
 *
 */
public class JMXQuery {

	private String url;
	private String pid;
	private int verbatim;
	private JMXConnector connector;
	private MBeanServerConnection connection;
	private long warning, critical;
	private String attribute, info_attribute;
	private String avg_data_path, rate_data_path;
	private long data_time = 600;
	private String attribute_key, info_key;
	private String username, password;
	private String object;

	private long checkData;
	private Object infoData;
	private Map<Long,Long> savedChecks;

	private static final int RETURN_OK = 0; // 	 The plugin was able to check the service and it appeared to be functioning properly
	private static final String OK_STRING = "JMX OK";
	private static final int RETURN_WARNING = 1; // The plugin was able to check the service, but it appeared to be above some "warning" threshold or did not appear to be working properly
	private static final String WARNING_STRING = "JMX WARNING";
	private static final int RETURN_CRITICAL = 2; // The plugin detected that either the service was not running or it was above some "critical" threshold
	private static final String CRITICAL_STRING = "JMX CRITICAL";
	private static final int RETURN_UNKNOWN = 3; // Invalid command line arguments were supplied to the plugin or low-level failures internal to the plugin (such as unable to fork, or open a tcp socket) that prevent it from performing the specified operation. Higher-level errors (such as name resolution errors, socket timeouts, etc) are outside of the control of plugins and should generally NOT be reported as UNKNOWN states.
	private static final String UNKNOWN_STRING = "JMX UNKNOWN";
	private static final String CONNECTOR_ADDRESS = "com.sun.management.jmxremote.localConnectorAddress";


	private void connect() throws IOException, com.sun.tools.attach.AttachNotSupportedException
	{
         if (pid != null) {
         	final VirtualMachine vm = VirtualMachine.attach(pid);
         	url = vm.getAgentProperties().getProperty(CONNECTOR_ADDRESS);
         	assert url != null;
         }
         JMXServiceURL jmxUrl = new JMXServiceURL(url);
         Map<String,Object> env = new HashMap<String,Object>();
         if (username != null) {
         	env.put("jmx.remote.credentials", new String[] {username, password});
         }
         connector = JMXConnectorFactory.connect(jmxUrl, env);
         connection = connector.getMBeanServerConnection();
	}


	private void disconnect() throws IOException {
		if(connector!=null){
			connector.close();
			connector = null;
		}
	}


	/**
	 * @param args
	 */
	public static void main(String[] args) {

			JMXQuery query = new JMXQuery();

			try{
				query.parse(args);
				query.connect();
				query.execute();
				int status = query.report(System.out);
				System.exit(status);
			}catch(Exception ex){
				int status = query.report(ex, System.out);
				System.exit(status);
			}finally{
				try {
					query.disconnect();
				} catch (IOException e) {
					int status = query.report(e, System.out);
					System.exit(status);
				}
			}
		}

	private int report(Exception ex, PrintStream out)
	{
		if(ex instanceof ParseError){
			out.print(UNKNOWN_STRING+" ");
			reportException(ex, out);
			out.println(" Usage: check_jmx -help ");
			return RETURN_UNKNOWN;
		}else{
			out.print(CRITICAL_STRING+" ");
			reportException(ex, out);
			out.println();
			return RETURN_CRITICAL;
		}
	}

	private void reportException(Exception ex, PrintStream out) {

		if(verbatim<2)
			out.print(rootCause(ex).getMessage());
		else{
			out.print(ex.getMessage()+" connecting to "+object+" by URL "+url);
		}


		if(verbatim>=3)
			ex.printStackTrace(out);

	}


	private static Throwable rootCause(Throwable ex) {
		if(ex.getCause()==null)
			return ex;
		return rootCause(ex.getCause());
	}


	private int report(PrintStream out)
	{
		int status;

		if(avg_data_path!=null){
			appendCheck(avg_data_path, checkData);
			checkData = (long)average();
		}else if(rate_data_path!=null){
			appendCheck(rate_data_path, checkData);
			checkData = (long)rate();
		}

		if(compare( critical, warning<critical)){
			status = RETURN_CRITICAL;
			out.print(CRITICAL_STRING+" ");
		}else if (compare( warning, warning<critical)){
			status = RETURN_WARNING;
			out.print(WARNING_STRING+" ");
		}else{
			status = RETURN_OK;
			out.print(OK_STRING+" ");
		}

		if(infoData==null || verbatim>=2){
			if(attribute_key!=null)
				out.print(attribute+'.'+attribute_key+'='+checkData);
			else if(avg_data_path!=null)
				out.print(attribute+"[avg]="+checkData);
			else if(rate_data_path!=null)
				out.print(attribute+"[/min]="+checkData);
			else
				out.print(attribute+'='+checkData);
		}

		if(infoData!=null){
			if(infoData instanceof CompositeDataSupport)
				report((CompositeDataSupport)infoData, out);
			else
				out.print(infoData.toString());
		}

		out.println();
		return status;
	}

	private void appendCheck(String path, long checkData) {
		readChecks(path);
		savedChecks.put(System.currentTimeMillis(), checkData);
		for(Long t : savedChecks.keySet()){
			if(t < System.currentTimeMillis() - data_time * 1000){
				savedChecks.remove(t);
			}
		}
		writeChecks(path);
	}
	private void readChecks(String path) {
		try{
			ObjectInputStream in = new ObjectInputStream(new FileInputStream(path));
			savedChecks = (Map<Long,Long>)in.readObject();
			in.close();
		}catch (Exception e){
			savedChecks = new HashMap<Long,Long>();
		}
	}
	private void writeChecks(String path) {
		try{
			ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(path));
			out.writeObject(savedChecks);
			out.close();
		}catch (Exception e){
			System.out.println(e);
		}
	}
	private double average() {
		double sum = 0;
		for(long v : savedChecks.values()){
			sum += v;
		}
		return sum / savedChecks.values().size();
	}
	private double rate() {
		long earliest = Collections.min(savedChecks.keySet());
		long latest = Collections.max(savedChecks.keySet());
		return (savedChecks.get(latest) - savedChecks.get(earliest)) / (data_time / 60);
	}

	private void report(CompositeDataSupport data, PrintStream out) {
		CompositeType type = data.getCompositeType();
		out.print('{');
		for(Iterator it = type.keySet().iterator();it.hasNext();){
			String key = (String) it.next();
			if(data.containsKey(key))
				out.print(key+'='+data.get(key));
			if(it.hasNext())
				out.print(';');
		}
		out.print('}');
	}


	private boolean compare(long level, boolean more) {
		if(more)
			return checkData>=level;
		else
			return checkData<=level;
	}


	private void execute() throws Exception{
		Object attr = connection.getAttribute(new ObjectName(object), attribute);
		if(attr instanceof CompositeDataSupport){
			CompositeDataSupport cds = (CompositeDataSupport) attr;
			if(attribute_key==null)
				throw new ParseError("Attribute key is null for composed data "+object);
			checkData = parseData(cds.get(attribute_key));
		} else if (attr instanceof Set) {
			Set set = (Set) attr;
			checkData = set.size();
		} else if (attr instanceof Map) {
			Map map = (Map) attr;
			checkData = map.size();
		} else if (attr instanceof Object[]) {
			Object[] objects = (Object[]) attr;
			checkData = Arrays.asList(objects).size();
		} else{
			checkData = parseData(attr);
		}

		if(info_attribute!=null){
			Object info_attr = info_attribute.equals(attribute) ?
									attr :
									connection.getAttribute(new ObjectName(object), info_attribute);
			if(info_key!=null && (info_attr instanceof CompositeDataSupport) && verbatim<4){
				CompositeDataSupport cds = (CompositeDataSupport) attr;
				infoData = cds.get(info_key);
			}else{
				infoData = info_attr;
			}
		}

	}

	private long parseData(Object o) {

		if (o instanceof Number) {
			return ((Number)o).longValue();
		}
		else if (o instanceof Boolean) {
			boolean b = ((Boolean)o).booleanValue();
			return b ? 1 : 0;
		}
		else {
			return Long.parseLong(o.toString());
		}
	}


	private void parse(String[] args) throws ParseError
	{
		try{
			for(int i=0;i<args.length;i++){
				String option = args[i];
				if(option.equals("-help"))
				{
					printHelp(System.out);
					System.exit(RETURN_UNKNOWN);
				}else if(option.equals("-U")){
					this.url = args[++i];
				}else if(option.equals("-P")){
					this.pid = args[++i];
				}else if(option.equals("-O")){
					this.object = args[++i];
				}else if(option.equals("-A")){
					this.attribute = args[++i];
				}else if(option.equals("-a")){
					this.avg_data_path = args[++i];
				}else if(option.equals("-r")){
					this.rate_data_path = args[++i];
				}else if(option.equals("-t")){
					this.data_time = Long.parseLong(args[++i]);
				}else if(option.equals("-I")){
					this.info_attribute = args[++i];
				}else if(option.equals("-J")){
					this.info_key = args[++i];
				}else if(option.equals("-K")){
					this.attribute_key = args[++i];
				}else if(option.equals("-u")){
					this.username = args[++i];
				}else if(option.equals("-p")){
					this.password = args[++i];
				}else if(option.startsWith("-v")){
					this.verbatim = option.length()-1;
				}else if(option.equals("-w")){
					this.warning = Long.parseLong(args[++i]);
				}else if(option.equals("-c")){
					this.critical = Long.parseLong(args[++i]);
				}
			}

			if((url==null && pid==null) || object==null || attribute==null)
				throw new Exception("Required options not specified");

		}catch(Exception e){
			throw new ParseError(e);
		}

	}


	private void printHelp(PrintStream out) {
		InputStream is = getClass().getClassLoader().getResourceAsStream("org/nagios/Help.txt");
		BufferedReader reader = new BufferedReader(new InputStreamReader(is));
		try{
			while(true){
				String s = reader.readLine();
				if(s==null)
					break;
				out.println(s);
			}
		} catch (IOException e) {
			out.println(e);
		}finally{
			try {
				reader.close();
			} catch (IOException e) {
				out.println(e);
			}
		}
	}



}
