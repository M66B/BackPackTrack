<?php
/*
Plugin Name:	BackPackTrack
Plugin URI:	 	http://blog.bokhorst.biz/
Description:	BackPackTrack XML-RPC methods
Version:		0.0
Author:		 	Marcel Bokhorst
Author URI:	 	http://blog.bokhorst.biz/about/
*/

add_filter('xmlrpc_methods', 'bpt_xmlrpc_methods');

function bpt_xmlrpc_methods($methods) {
	$methods['bpt.upload'] = 'bpt_upload';
	return $methods;
}

function bpt_upload($args) {
	try {
		global $wpdb;
		global $wp_xmlrpc_server;

		// Decode arguments
		$blog_ID = (int) $args[0];
		$username = $wpdb->escape($args[1]);
		$password = $wpdb->escape($args[2]);
		$data = $args[3];

		$name = sanitize_file_name($data['name']);
		$type = $data['type'];
		$bits = $data['bits'];

		logIO('O', 'bpt.upload ' . $name . ' ' . strlen($bits) . ' bytes');

		// Check credentials
		if (!$user = $wp_xmlrpc_server->login($username, $password)) {
			logIO('O', 'bpt.upload invalid login');
			return $wp_xmlrpc_server->error;
		}

		// Check user capabilities
		if (!current_user_can('upload_files')) {
			logIO('O', 'bpt.upload no capability');
			return new IXR_Error(401, __('You are not allowed to upload files to this site.'));
		}

		// Handle overwrite
		if (!empty($data['overwrite']) && ($data['overwrite'] == true)) {
			logIO('O', 'bpt.upload overwrite');
			$old_file = $wpdb->get_row("SELECT ID FROM {$wpdb->posts} WHERE post_title = '{$name}' AND post_type = 'attachment'");
			wp_delete_attachment($old_file->ID);
		}

		// Save file
		$upload = wp_upload_bits($name, NULL, $bits);
		if (!empty($upload['error'])) {
			$error = sprintf(__('Could not write file %1$s (%2$s)'), $name, $upload['error']);
			logIO('O', 'bpt.upload (MW) ' . $error);
			return new IXR_Error(500, $error);
		}

		// Attach file
		$post_id = 0;
		$attachment = array(
			'post_title' => $name,
			'post_content' => '',
			'post_type' => 'attachment',
			'post_parent' => $post_id,
			'post_mime_type' => $type,
			'guid' => $upload['url']
		);
		$id = wp_insert_attachment($attachment, $upload['file'], $post_id);
		wp_update_attachment_metadata($id, wp_generate_attachment_metadata($id, $upload['file']));

		logIO('O', 'bpt.upload attachment=' . $id);

		// Handle upload
		return apply_filters('wp_handle_upload', array('file' => $name, 'url' => $upload['url'], 'type' => $type), 'upload');
	}
	catch (Exception $e) {
		// What?
		logIO('O', 'bpt.upload exception' . $e->getMessage());
		return new IXR_Error(500, $e->getMessage());
	}
}

?>
