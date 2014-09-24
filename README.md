# rooty-toots

Simple, useful handlers for the rooty-tooty framework.

## DnsHandler
Manages a djbdns (aka tinydns) data file. Accepts add/remove messages to update file contents, then reloads configuration (via `svc -h /path/to/tinydns`)

## PostfixHandler
Manages a postfix installation. Supports:

* Add account
* Remove account
* Add domain
* Remove domain

For simplicity, when adding a user, they are automatically added to all domains. Likewise, when adding a domain, all users will become members of the domain.

## ChefHandler
Manages chef cookbooks and runs chef-solo. Supports:

* Add recipe to run-list
* Remove recipe from run-list

## ServiceKeyHandler
Used by CloudOs to issue and manage service keys (valet-style SSH keys for a CloudOs instance)

## SslCertHandler
Manages SSL certificates

## SystemHandler
Manages some OS-level settings, currently only the system time zones
 
## VendorSettingHandler
Used by CloudOs to manage settings within chef data bags.

##### License
For personal or non-commercial use, this code is available under the [GNU Affero General Public License, version 3](https://www.gnu.org/licenses/agpl-3.0.html).
For commercial use, please contact cloudstead.io
